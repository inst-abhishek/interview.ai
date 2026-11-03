package com.exl.interview.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:hiring-process-tests;DB_CLOSE_DELAY=-1",
        "interview.admin-setup-code=process-admin",
        "interview.invitation-key-file=./target/process-test.key",
        "interview.catalogue-enabled=true",
        "logging.level.root=ERROR"
    }
)
@AutoConfigureMockMvc
@Transactional
class HiringProcessesTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate db;

    @Autowired
    HiringProcesses processes;

    @MockBean
    LiveInterviewAi ai;

    String admin, recruiter, other, reviewer, reviewerId, otherReviewer, otherReviewerId;
    String job1, job2, plan;
    final String version = "java-backend-mid-v1";

    JsonNode post(String path, String token, Object body, int expected) throws Exception {
        var request = MockMvcRequestBuilders.post("/api" + path)
            .contentType("application/json")
            .content(json.writeValueAsString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        String text = mvc
            .perform(request)
            .andExpect(status().is(expected))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return text.isBlank() ? json.createObjectNode() : json.readTree(text);
    }

    JsonNode get(String path, String token, int expected) throws Exception {
        var request = MockMvcRequestBuilders.get("/api" + path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return json.readTree(
            mvc
                .perform(request)
                .andExpect(status().is(expected))
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
    }

    JsonNode candidate(String code, String suffix, Object body, int expected) throws Exception {
        var request =
            body == null
                ? MockMvcRequestBuilders.get("/api/process-candidate" + suffix)
                : MockMvcRequestBuilders.post("/api/process-candidate" + suffix)
                      .contentType("application/json")
                      .content(json.writeValueAsString(body));
        request.header("X-Process-Code", code);
        return json.readTree(
            mvc
                .perform(request)
                .andExpect(status().is(expected))
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
    }

    String account(String email, String role) throws Exception {
        return post(
            "/staff/accounts",
            admin,
            Map.of(
                "name",
                role + " Person",
                "email",
                email,
                "password",
                "process-password-123",
                "role",
                role
            ),
            200
        )
            .path("id")
            .asText();
    }

    String login(String email) throws Exception {
        return post(
            "/staff/login",
            null,
            Map.of("email", email, "password", "process-password-123"),
            200
        )
            .path("token")
            .asText();
    }

    @BeforeEach
    void setup() throws Exception {
        when(ai.available()).thenReturn(true);
        when(ai.clarify(anyString(), anyString())).thenReturn(
            "Which failure case would you handle first, and why?"
        );
        admin = post(
            "/staff/bootstrap",
            null,
            Map.of(
                "name",
                "Admin",
                "email",
                "admin@process.test",
                "password",
                "process-password-123",
                "setupCode",
                "process-admin"
            ),
            200
        )
            .path("token")
            .asText();
        account("recruiter@process.test", "RECRUITER");
        account("other@process.test", "RECRUITER");
        reviewerId = account("reviewer@process.test", "REVIEWER");
        otherReviewerId = account("second@process.test", "REVIEWER");
        recruiter = login("recruiter@process.test");
        other = login("other@process.test");
        reviewer = login("reviewer@process.test");
        otherReviewer = login("second@process.test");
        job1 = job("Assessment 1");
        job2 = job("Assessment 2");
        post("/staff/assignments/" + job1, admin, Map.of("reviewerId", reviewerId), 200);
        post("/staff/assignments/" + job2, admin, Map.of("reviewerId", reviewerId), 200);
        db.update("UPDATE assessment_templates SET status='APPROVED' WHERE version=?", version);
        db.update(
            "MERGE INTO template_review_assignments KEY(version) VALUES(?,?)",
            version,
            reviewerId
        );
        plan = post("/review/ai-plans/" + version + "/approve", reviewer, Map.of(), 200)
            .path("version")
            .asText();
    }

    String job(String title) throws Exception {
        return post(
            "/recruiter/interviews",
            recruiter,
            Map.of(
                "title",
                title,
                "description",
                "Technical assessment",
                "durationMinutes",
                30,
                "scheduledAt",
                0,
                "questionIds",
                List.of("q2")
            ),
            200
        )
            .path("id")
            .asText();
    }

    @Test
    void recruiterCanAddAiWithoutAnyPlanApproval() throws Exception {
        db.update("DELETE FROM ai_interview_plans");
        var result = post(
            "/recruiter/processes",
            recruiter,
            Map.of(
                "title",
                "AI requested",
                "interviewIds",
                List.of(job1),
                "aiEnabled",
                true,
                "aiFocus",
                "Java and React",
                "aiMinutes",
                15
            ),
            200
        );
        assertEquals(2, result.path("rounds").size());
        String processId = result.path("process").path("ID").asText();
        assertEquals(
            "AI_PRELIMINARY",
            db.queryForObject(
                "SELECT kind FROM hiring_rounds WHERE process_id=? AND position=0",
                String.class,
                processId
            )
        );
    }

    @Test
    void candidateConnectorRejectsDisabledModeRemoteRequestsAndMissingInvitation()
        throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/process-candidate/connection")).andExpect(
            status().isForbidden()
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
            processes,
            "candidateAiDemo",
            true
        );
        try {
            mvc.perform(
                MockMvcRequestBuilders.get("/api/process-candidate/connection").with(request -> {
                    request.setRemoteAddr("192.168.1.10");
                    return request;
                })
            ).andExpect(status().isForbidden());
            mvc.perform(
                MockMvcRequestBuilders.get("/api/process-candidate/connection").with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                })
            ).andExpect(status().isUnauthorized());
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                processes,
                "candidateAiDemo",
                false
            );
        }
    }

    String process(boolean withAi) throws Exception {
        return post(
            "/recruiter/processes",
            recruiter,
            Map.of(
                "title",
                "Java hiring process",
                "interviewIds",
                List.of(job1, job2),
                "aiPlanVersion",
                withAi ? plan : "",
                "aiMinutes",
                15
            ),
            200
        )
            .path("process")
            .path("ID")
            .asText();
    }

    @Test
    void addAndInviteReusesProfilePreservesOptionalDetailsAndRollsBackInvalidInvite()
        throws Exception {
        String process = process(false);
        String path = "/recruiter/processes/" + process + "/add-invite";
        long expiry = System.currentTimeMillis() + 30L * 86400000;
        post(
            path,
            recruiter,
            Map.of(
                "name",
                "New candidate",
                "email",
                "new@example.com",
                "skills",
                "Java, Angular",
                "experience",
                2.5,
                "expiresAt",
                expiry
            ),
            200
        );
        var profile = db.queryForMap("SELECT * FROM candidate_pool WHERE email='new@example.com'");
        assertEquals("Java, Angular", profile.get("skills"));
        assertEquals(2.5, ((Number) profile.get("experience")).doubleValue());
        var repeated = post(
            path,
            recruiter,
            Map.of(
                "name",
                "Changed name",
                "email",
                "NEW@example.com",
                "skills",
                "Other",
                "experience",
                0,
                "expiresAt",
                expiry
            ),
            200
        );
        assertEquals(1, repeated.path("alreadyInvited").asInt());
        assertEquals(
            "New candidate",
            db.queryForObject(
                "SELECT name FROM candidate_pool WHERE id=?",
                String.class,
                profile.get("id")
            )
        );
        assertEquals(
            2.5,
            db.queryForObject(
                "SELECT experience FROM candidate_pool WHERE id=?",
                Double.class,
                profile.get("id")
            )
        );
        post(
            path,
            recruiter,
            Map.of("name", "Missing profile", "email", "missing@example.com", "expiresAt", expiry),
            200
        );
        assertNull(
            db.queryForObject(
                "SELECT experience FROM candidate_pool WHERE email='missing@example.com'",
                Double.class
            )
        );
        post(
            path,
            recruiter,
            Map.of("name", "Invalid invite", "email", "rollback@example.com", "expiresAt", 1),
            400
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM candidate_pool WHERE email='rollback@example.com'",
                Integer.class
            )
        );
        post(
            path,
            recruiter,
            Map.of(
                "name",
                "Invalid experience",
                "email",
                "invalid@example.com",
                "experience",
                -1,
                "expiresAt",
                expiry
            ),
            400
        );
    }

    @Test
    void processSelectorPaginatesSearchesAndRestrictsToOwner() throws Exception {
        String owner = db.queryForObject(
            "SELECT id FROM recruiters WHERE email='recruiter@process.test'",
            String.class
        );
        for (int i = 0; i < 25; i++) db.update(
            "INSERT INTO hiring_processes(id,recruiter_id,title,created_at) VALUES(?,?,?,?)",
            "paged-" + i,
            owner,
            "Scalable process " + i,
            (long) i
        );
        var page = get("/recruiter/processes/page?search=Scalable", recruiter, 200);
        assertEquals(25, page.path("total").asInt());
        assertEquals(20, page.path("items").size());
        assertEquals(
            5,
            get("/recruiter/processes/page?search=Scalable&page=1", recruiter, 200)
                .path("items")
                .size()
        );
        assertEquals(
            0,
            get("/recruiter/processes/page?search=Scalable", other, 200).path("total").asInt()
        );
        assertEquals(
            0,
            get("/recruiter/processes/page?search=%25", recruiter, 200).path("total").asInt()
        );
    }

    @Test
    void assessmentProcessReminderIsOwnedAndReflectsActualAssociation() throws Exception {
        assertEquals(
            0,
            get("/recruiter/interviews/" + job1 + "/processes", recruiter, 200)
                .path("count")
                .asInt()
        );
        process(false);
        assertEquals(
            1,
            get("/recruiter/interviews/" + job1 + "/processes", recruiter, 200)
                .path("count")
                .asInt()
        );
        get("/recruiter/interviews/" + job1 + "/processes", other, 404);
    }

    @Test
    void assessmentInvitationsExposeSourceWithoutMergingAttempts() throws Exception {
        post(
            "/recruiter/interviews/" + job1 + "/invitations",
            recruiter,
            Map.of(
                "candidateName",
                "Standalone",
                "candidateEmail",
                "standalone@process.test",
                "expiresAt",
                System.currentTimeMillis() + 30L * 86400000
            ),
            200
        );
        String process = process(false);
        invite(
            process,
            db.queryForObject(
                "SELECT id FROM candidate_pool WHERE email='standalone@process.test'",
                String.class
            )
        );
        var invitations = get("/recruiter/interviews/" + job1 + "/candidates", recruiter, 200);
        assertEquals(2, invitations.size());
        for (var item : invitations) {
            assertTrue(item.path("invitedAt").asLong() > 0);
            assertEquals("standalone@process.test", item.path("candidateEmail").asText());
            if (item.path("source").asText().equals("STANDALONE")) {
                assertEquals("STANDALONE", item.path("source").asText());
                assertTrue(item.path("processId").isNull());
            } else {
                assertEquals("HIRING_PROCESS", item.path("source").asText());
                assertEquals(process, item.path("processId").asText());
                assertEquals(1, item.path("roundNumber").asInt());
                assertEquals(2, item.path("totalRounds").asInt());
            }
        }
        get("/recruiter/interviews/" + job1 + "/candidates", other, 404);
    }

    String pool(String name) throws Exception {
        return post(
            "/recruiter/pool",
            recruiter,
            Map.of("name", name, "email", name.toLowerCase(Locale.ROOT) + "@candidate.test"),
            200
        )
            .path("id")
            .asText();
    }

    String invite(String process, String poolId) throws Exception {
        post(
            "/recruiter/processes/" + process + "/candidates",
            recruiter,
            Map.of(
                "candidateIds",
                List.of(poolId),
                "expiresAt",
                System.currentTimeMillis() + 7 * 86400000L
            ),
            200
        );
        return db.queryForObject(
            "SELECT id FROM process_candidates WHERE process_id=? AND candidate_id=?",
            String.class,
            process,
            poolId
        );
    }

    String code(String process, String participant) throws Exception {
        String url = get(
            "/recruiter/processes/" + process + "/candidates/" + participant + "/link",
            recruiter,
            200
        )
            .path("url")
            .asText();
        return url.substring(url.indexOf("#process=") + 9);
    }

    String attempt(String participant, int position) {
        return db.queryForObject(
            "SELECT a.id FROM process_attempts a JOIN hiring_rounds r ON r.id=a.round_id WHERE participant_id=? AND position=?",
            String.class,
            participant,
            position
        );
    }

    String state(String attempt) {
        return db.queryForObject(
            "SELECT state FROM process_attempts WHERE id=?",
            String.class,
            attempt
        );
    }

    @Test
    void assessmentOnlyProcessKeepsNormalInvitationsAndRequiresPublishedFeedbackBeforeRoundTwo()
        throws Exception {
        String process = process(false),
            person = invite(process, pool("First"));
        String first = attempt(person, 0),
            second = attempt(person, 1);
        assertEquals("INVITED", state(first));
        assertEquals("LOCKED", state(second));
        post(
            "/recruiter/processes/" + process + "/candidates/" + person + "/decision",
            recruiter,
            Map.of("action", "INVITE_NEXT"),
            409
        );
        String invitation = db.queryForObject(
            "SELECT invitation_id FROM process_attempts WHERE id=?",
            String.class,
            first
        );
        String session = UUID.randomUUID().toString();
        db.update(
            "INSERT INTO sessions(id,state,questions,interview_id,invitation_id) VALUES(?,'SUBMITTED','[]',?,?)",
            session,
            job1,
            invitation
        );
        db.update("UPDATE invitations SET session_id=? WHERE id=?", session, invitation);
        long submittedAt = System.currentTimeMillis();
        db.update("UPDATE sessions SET submitted_at=? WHERE id=?", submittedAt, session);
        var receipt = candidate(code(process, person), "", null, 200).path("rounds").get(0);
        assertEquals(session, receipt.path("submissionReference").asText());
        assertEquals(submittedAt, receipt.path("submittedAt").asLong());
        assertFalse(receipt.has("assessmentUrl"));
        post(
            "/recruiter/processes/" + process + "/candidates/" + person + "/decision",
            recruiter,
            Map.of("action", "INVITE_NEXT"),
            409
        );
        db.update(
            "INSERT INTO reviews(session_id,reviewer,score,notes,recommendation,reviewed_at) VALUES(?,?,3,'','HOLD',?)",
            session,
            reviewerId,
            System.currentTimeMillis()
        );
        post(
            "/recruiter/processes/" + process + "/candidates/" + person + "/decision",
            recruiter,
            Map.of("action", "INVITE_NEXT"),
            200
        );
        assertEquals("FEEDBACK_PUBLISHED", state(first));
        var progress = get("/recruiter/processes/" + process + "/candidates", recruiter, 200)
            .path("items")
            .get(0)
            .path("steps");
        assertEquals("HOLD", progress.get(0).path("RECOMMENDATION").asText());
        assertTrue(progress.get(1).path("RECOMMENDATION").isNull());
        assertFalse(
            candidate(code(process, person), "", null, 200)
                .path("rounds")
                .get(0)
                .has("assessmentUrl")
        );
        assertEquals("INVITED", state(second));
        assertTrue(
            candidate(code(process, person), "", null, 200)
                .path("rounds")
                .get(1)
                .has("assessmentUrl")
        );
        verify(ai, never()).clarify(anyString(), anyString());
    }

    @Test
    void skippingReadyOrInProgressAiPreservesAnswersAndMakesAssessmentReady() throws Exception {
        String process = process(true),
            person = invite(process, pool("First")),
            token = code(process, person),
            aiAttempt = attempt(person, 0);
        var started = candidate(token, "/" + aiAttempt + "/start", Map.of("consent", true), 200);
        String question = started
            .path("rounds")
            .get(0)
            .path("transcript")
            .get(0)
            .path("id")
            .asText();
        candidate(
            token,
            "/" + aiAttempt + "/answer",
            Map.of(
                "questionId",
                question,
                "text",
                "Use bounded retries and timeouts.",
                "requestId",
                "answer-request-001"
            ),
            200
        );
        String transcript = db.queryForObject(
            "SELECT transcript FROM process_attempts WHERE id=?",
            String.class,
            aiAttempt
        );
        post(
            "/recruiter/processes/" + process + "/candidates/" + person + "/skip-ai",
            recruiter,
            Map.of("reason", "Proceed directly to assessment", "includeFuture", false),
            200
        );
        assertEquals("SKIPPED", state(aiAttempt));
        assertEquals(
            transcript,
            db.queryForObject(
                "SELECT transcript FROM process_attempts WHERE id=?",
                String.class,
                aiAttempt
            )
        );
        assertEquals("READY", state(attempt(person, 1)));
        candidate(
            token,
            "/" + aiAttempt + "/answer",
            Map.of(
                "questionId",
                question,
                "text",
                "Late answer",
                "requestId",
                "answer-request-002"
            ),
            409
        );
        post(
            "/recruiter/processes/" + process + "/candidates/" + person + "/decision",
            recruiter,
            Map.of("action", "INVITE_NEXT"),
            200
        );
        assertEquals("INVITED", state(attempt(person, 1)));
    }

    @Test
    void bulkSkipAppliesToAllInvitedCandidatesAndOptionallyToFutureInvitations() throws Exception {
        String process = process(true);
        String first = invite(process, pool("First")),
            second = invite(process, pool("Second"));
        var result = post(
            "/recruiter/processes/" + process + "/skip-ai",
            recruiter,
            Map.of("reason", "Use the assessment directly", "includeFuture", true),
            200
        );
        assertEquals(2, result.path("skipped").asInt());
        assertEquals("SKIPPED", state(attempt(first, 0)));
        assertEquals("SKIPPED", state(attempt(second, 0)));
        String third = invite(process, pool("Third"));
        assertEquals("SKIPPED", state(attempt(third, 0)));
        assertEquals("INVITED", state(attempt(third, 1)));
        post(
            "/recruiter/processes/" + process + "/ai-policy",
            recruiter,
            Map.of("skipFuture", false),
            200
        );
        String fourth = invite(process, pool("Fourth"));
        assertEquals("READY", state(attempt(fourth, 0)));
        assertEquals("SKIPPED", state(attempt(first, 0)));
    }

    @Test
    void aDuplicateInviteDoesNotCreateAnotherAttemptOrToken() throws Exception {
        String process = process(true),
            pool = pool("First");
        String person = invite(process, pool);
        var duplicate = post(
            "/recruiter/processes/" + process + "/candidates",
            recruiter,
            Map.of(
                "candidateIds",
                List.of(pool),
                "expiresAt",
                System.currentTimeMillis() + 7 * 86400000L
            ),
            200
        );
        assertEquals(0, duplicate.path("created").asInt());
        assertEquals(1, duplicate.path("alreadyInvited").asInt());
        assertEquals(
            3,
            db.queryForObject(
                "SELECT COUNT(*) FROM process_attempts WHERE participant_id=?",
                Integer.class,
                person
            )
        );
    }

    @Test
    void aiAnswersAreIdempotentAndFollowUpsAreBoundedByTheApprovedPlan() throws Exception {
        String process = process(true),
            person = invite(process, pool("First")),
            token = code(process, person),
            aiAttempt = attempt(person, 0);
        candidate(token, "/" + aiAttempt + "/start", Map.of("consent", false), 409);
        var started = candidate(token, "/" + aiAttempt + "/start", Map.of("consent", true), 200);
        String question = started
            .path("rounds")
            .get(0)
            .path("transcript")
            .get(0)
            .path("id")
            .asText();
        var body = Map.of(
            "questionId",
            question,
            "text",
            "A technical response.",
            "requestId",
            "answer-request-001"
        );
        candidate(token, "/" + aiAttempt + "/answer", body, 200);
        candidate(token, "/" + aiAttempt + "/answer", body, 200);
        var next = candidate(token, "/" + aiAttempt + "/next", Map.of(), 200);
        assertEquals(3, next.path("rounds").get(0).path("transcript").size());
        assertTrue(
            next.path("rounds").get(0).path("transcript").get(2).path("followUp").asBoolean()
        );
        candidate(token, "/" + aiAttempt + "/next", Map.of(), 200);
        verify(ai, times(1)).clarify(anyString(), eq("A technical response."));
        question = next.path("rounds").get(0).path("transcript").get(2).path("id").asText();
        candidate(
            token,
            "/" + aiAttempt + "/answer",
            Map.of(
                "questionId",
                question,
                "text",
                "Further detail.",
                "requestId",
                "answer-request-002"
            ),
            200
        );
        next = candidate(token, "/" + aiAttempt + "/next", Map.of(), 200);
        assertEquals(1, next.path("rounds").get(0).path("transcript").get(4).path("topic").asInt());
        assertFalse(
            next.path("rounds").get(0).path("transcript").get(4).path("followUp").asBoolean()
        );
        verify(ai, times(1)).clarify(anyString(), anyString());
    }

    @Test
    void recruiterSkipWinsOverAnInFlightAiResponse() throws Exception {
        String process = process(true),
            person = invite(process, pool("First")),
            token = code(process, person),
            aiAttempt = attempt(person, 0);
        var started = candidate(token, "/" + aiAttempt + "/start", Map.of("consent", true), 200);
        String question = started
            .path("rounds")
            .get(0)
            .path("transcript")
            .get(0)
            .path("id")
            .asText();
        candidate(
            token,
            "/" + aiAttempt + "/answer",
            Map.of(
                "questionId",
                question,
                "text",
                "A technical answer.",
                "requestId",
                "answer-request-001"
            ),
            200
        );
        when(ai.clarify(anyString(), anyString())).thenAnswer(call -> {
            post(
                "/recruiter/processes/" + process + "/candidates/" + person + "/skip-ai",
                recruiter,
                Map.of("reason", "Recruiter override", "includeFuture", false),
                200
            );
            return "A late follow-up?";
        });
        candidate(token, "/" + aiAttempt + "/next", Map.of(), 409);
        assertEquals("SKIPPED", state(aiAttempt));
        assertEquals(
            2,
            json
                .readTree(
                    db.queryForObject(
                        "SELECT transcript FROM process_attempts WHERE id=?",
                        String.class,
                        aiAttempt
                    )
                )
                .size()
        );
    }

    @Test
    void serverExpiryRetainsSavedDraftAndOnlyAssignedReviewerCanAccessIt() throws Exception {
        String process = process(true),
            person = invite(process, pool("First")),
            token = code(process, person),
            aiAttempt = attempt(person, 0);
        var started = candidate(token, "/" + aiAttempt + "/start", Map.of("consent", true), 200);
        String question = started
            .path("rounds")
            .get(0)
            .path("transcript")
            .get(0)
            .path("id")
            .asText();
        candidate(
            token,
            "/" + aiAttempt + "/draft",
            Map.of("questionId", question, "text", "My saved unfinished response", "revision", 0),
            200
        );
        db.update(
            "UPDATE process_attempts SET deadline=? WHERE id=?",
            System.currentTimeMillis() - 1,
            aiAttempt
        );
        processes.expire();
        assertEquals("SUBMITTED", state(aiAttempt));
        var review = get("/review/ai-rounds/" + aiAttempt, reviewer, 200);
        assertEquals(
            "My saved unfinished response",
            review.path("attempt").path("DRAFT_TEXT").asText()
        );
        get("/review/ai-rounds/" + aiAttempt, otherReviewer, 404);
        var candidateView = candidate(token, "", null, 200).toString();
        assertFalse(candidateView.contains("review_marks"));
        assertFalse(candidateView.contains("review_notes"));
        assertEquals("LOCKED", state(attempt(person, 1)));
    }

    @Test
    void humanPublicationIsRequiredAndSkippingAfterPublicationKeepsFeedback() throws Exception {
        String process = process(true),
            person = invite(process, pool("First")),
            token = code(process, person),
            aiAttempt = attempt(person, 0);
        candidate(token, "/" + aiAttempt + "/start", Map.of("consent", true), 200);
        candidate(token, "/" + aiAttempt + "/finish", Map.of(), 200);
        post(
            "/review/ai-rounds/" + aiAttempt,
            reviewer,
            Map.of("marks", List.of(), "notes", "", "recommendation", "SHORTLIST", "publish", true),
            400
        );
        var view = get("/review/ai-rounds/" + aiAttempt, reviewer, 200);
        List<Map<String, Object>> marks = new ArrayList<>();
        for (var topic : view.path("plan").path("topics"))
            for (var criterion : topic.path("rubric")) {
                marks.add(
                    Map.of(
                        "questionId",
                        topic.path("id").asText(),
                        "criterionId",
                        criterion.path("id").asText(),
                        "points",
                        0
                    )
                );
            }
        post(
            "/review/ai-rounds/" + aiAttempt,
            reviewer,
            Map.of("marks", marks, "notes", "", "recommendation", "HOLD", "publish", true),
            200
        );
        assertEquals("FEEDBACK_PUBLISHED", state(aiAttempt));
        assertEquals("LOCKED", state(attempt(person, 1)));
        String savedMarks = db.queryForObject(
            "SELECT review_marks FROM process_attempts WHERE id=?",
            String.class,
            aiAttempt
        );
        post(
            "/recruiter/processes/" + process + "/candidates/" + person + "/skip-ai",
            recruiter,
            Map.of("reason", "Proceed without the AI gate", "includeFuture", false),
            409
        );
        assertEquals(
            savedMarks,
            db.queryForObject(
                "SELECT review_marks FROM process_attempts WHERE id=?",
                String.class,
                aiAttempt
            )
        );
        assertEquals(
            reviewerId,
            db.queryForObject(
                "SELECT published_by FROM process_attempts WHERE id=?",
                String.class,
                aiAttempt
            )
        );
        post(
            "/review/ai-rounds/" + aiAttempt,
            reviewer,
            Map.of("marks", marks, "notes", "", "recommendation", "HOLD", "publish", false),
            409
        );
    }

    @Test
    void processOwnershipHoldAndUnavailableAiAreEnforcedWithoutBlockingRecruiterSkip()
        throws Exception {
        String process = process(true),
            person = invite(process, pool("First")),
            token = code(process, person),
            aiAttempt = attempt(person, 0);
        get("/recruiter/processes/" + process, other, 404);
        post(
            "/recruiter/processes/" + process + "/skip-ai",
            other,
            Map.of("reason", "Unauthorized", "includeFuture", true),
            404
        );
        when(ai.available()).thenReturn(false);
        candidate(token, "/" + aiAttempt + "/start", Map.of("consent", true), 503);
        post(
            "/recruiter/processes/" + process + "/candidates/" + person + "/decision",
            recruiter,
            Map.of("action", "HOLD"),
            200
        );
        candidate(token, "/" + aiAttempt + "/start", Map.of("consent", true), 409);
        post(
            "/recruiter/processes/" + process + "/candidates/" + person + "/skip-ai",
            recruiter,
            Map.of("reason", "AI is unavailable", "includeFuture", false),
            200
        );
        assertEquals("SKIPPED", state(aiAttempt));
    }

    @Test
    void administratorReassignmentMovesTheAiReviewQueue() throws Exception {
        String process = process(true),
            person = invite(process, pool("First")),
            token = code(process, person),
            aiAttempt = attempt(person, 0);
        candidate(token, "/" + aiAttempt + "/start", Map.of("consent", true), 200);
        candidate(token, "/" + aiAttempt + "/finish", Map.of(), 200);
        post("/staff/assignments/" + job1, admin, Map.of("reviewerId", otherReviewerId), 200);
        get("/review/ai-rounds/" + aiAttempt, reviewer, 404);
        get("/review/ai-rounds/" + aiAttempt, otherReviewer, 200);
    }
}
