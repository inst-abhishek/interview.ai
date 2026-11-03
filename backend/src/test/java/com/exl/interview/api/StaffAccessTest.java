package com.exl.interview.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:staff-access;DB_CLOSE_DELAY=-1",
        "interview.admin-setup-code=private-admin-setup",
        "interview.reviewer-code=old-review-code",
        "interview.recruiter-setup-code=old-setup",
        "logging.level.root=ERROR"
    }
)
@AutoConfigureMockMvc
@Transactional
class StaffAccessTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate db;

    String admin;

    JsonNode post(String path, String token, Object body, int status) throws Exception {
        var r = MockMvcRequestBuilders.post("/api" + path)
            .contentType("application/json")
            .content(json.writeValueAsString(body));
        if (token != null) r.header("Authorization", "Bearer " + token);
        String data = mvc
            .perform(r)
            .andExpect(status().is(status))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return data.isBlank() ? json.createObjectNode() : json.readTree(data);
    }

    JsonNode get(String path, String token, int status) throws Exception {
        var r = MockMvcRequestBuilders.get("/api" + path);
        if (token != null) r.header("Authorization", "Bearer " + token);
        return json.readTree(
            mvc
                .perform(r)
                .andExpect(status().is(status))
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
    }

    Map<String, Object> create(String email, String role) {
        return Map.of(
            "name",
            role + " Person",
            "email",
            email,
            "password",
            "initial-password-123",
            "role",
            role
        );
    }

    @BeforeEach
    void setup() throws Exception {
        admin = post(
            "/staff/bootstrap",
            null,
            Map.of(
                "name",
                "Admin",
                "email",
                "admin@example.com",
                "password",
                "administrator-secret",
                "setupCode",
                "private-admin-setup"
            ),
            200
        )
            .get("token")
            .asText();
    }

    String login(String email) throws Exception {
        return post(
            "/staff/login",
            null,
            Map.of("email", email, "password", "initial-password-123"),
            200
        )
            .get("token")
            .asText();
    }

    @Test
    void bootstrapIsSingleUseAndRetiresSharedAccess() throws Exception {
        post(
            "/staff/bootstrap",
            null,
            Map.of(
                "name",
                "Other",
                "email",
                "other@example.com",
                "password",
                "administrator-secret",
                "setupCode",
                "private-admin-setup"
            ),
            409
        );
        post("/auth/reviewer-login", null, Map.of("code", "old-review-code"), 403);
        post(
            "/recruiter/register",
            null,
            Map.of(
                "name",
                "Old",
                "email",
                "old@example.com",
                "password",
                "initial-password-123",
                "setupCode",
                "old-setup"
            ),
            403
        );
        assertFalse(get("/staff/accounts", admin, 200).toString().contains("password_hash"));
    }

    @Test
    void rolesDeactivationAndRevocationAreEnforced() throws Exception {
        var a = post("/staff/accounts", admin, create("reviewer@example.com", "REVIEWER"), 200);
        String reviewer = login("reviewer@example.com");
        get("/staff/accounts", reviewer, 403);
        get("/recruiter/interviews", reviewer, 401);
        get("/review/sessions", reviewer, 200);
        post(
            "/staff/accounts/" + a.get("id").asText(),
            admin,
            Map.of("role", "REVIEWER", "active", false),
            200
        );
        get("/review/sessions", reviewer, 401);
        post(
            "/staff/login",
            null,
            Map.of("email", "reviewer@example.com", "password", "initial-password-123"),
            401
        );
        String adminId = get("/staff/me", admin, 200).get("id").asText();
        post("/staff/accounts/" + adminId, admin, Map.of("role", "RECRUITER", "active", true), 400);
    }

    @Test
    void resetIsExpiringSingleUseAndRevokesExistingSessions() throws Exception {
        var a = post("/staff/accounts", admin, create("hire@example.com", "RECRUITER"), 200);
        String old = login("hire@example.com"),
            id = a.get("id").asText();
        String expired = post("/staff/accounts/" + id + "/reset", admin, Map.of(), 200)
            .get("token")
            .asText();
        db.update("UPDATE password_resets SET expires=0 WHERE account_id=?", id);
        post(
            "/staff/reset-password",
            null,
            Map.of("token", expired, "password", "new-private-password"),
            400
        );
        String token = post("/staff/accounts/" + id + "/reset", admin, Map.of(), 200)
            .get("token")
            .asText();
        post(
            "/staff/reset-password",
            null,
            Map.of("token", token, "password", "new-private-password"),
            200
        );
        post(
            "/staff/reset-password",
            null,
            Map.of("token", token, "password", "another-private-password"),
            400
        );
        get("/recruiter/interviews", old, 401);
        post(
            "/staff/login",
            null,
            Map.of("email", "hire@example.com", "password", "new-private-password"),
            200
        );
    }

    @Test
    void assignmentPageFiltersAndPaginatesWithAdminOnlyReviewerSearch() throws Exception {
        post("/staff/accounts", admin, create("paged-recruiter@example.com", "RECRUITER"), 200);
        String recruiter = login("paged-recruiter@example.com");
        String reviewerId = post(
            "/staff/accounts",
            admin,
            create("paged-reviewer@example.com", "REVIEWER"),
            200
        )
            .path("id")
            .asText();
        String first = "";
        for (int i = 0; i < 11; i++) {
            String id = post(
                "/recruiter/interviews",
                recruiter,
                Map.of(
                    "title",
                    "Paged Java " + i,
                    "description",
                    "Role",
                    "durationMinutes",
                    30,
                    "scheduledAt",
                    0,
                    "questionIds",
                    List.of("q1")
                ),
                200
            )
                .path("id")
                .asText();
            if (i == 0) first = id;
        }
        var page = get("/staff/assignments/page?search=Paged", admin, 200);
        assertEquals(11, page.path("total").asInt());
        assertEquals(10, page.path("items").size());
        assertEquals(
            1,
            get("/staff/assignments/page?search=Paged&page=1", admin, 200).path("items").size()
        );
        post("/staff/assignments/" + first, admin, Map.of("reviewerId", reviewerId), 200);
        assertEquals(
            1,
            get("/staff/assignments/page?search=Paged&status=ASSIGNED", admin, 200)
                .path("total")
                .asInt()
        );
        assertEquals(
            10,
            get("/staff/assignments/page?search=Paged&status=UNASSIGNED", admin, 200)
                .path("total")
                .asInt()
        );
        assertEquals(
            0,
            get("/staff/assignments/page?search=%25", admin, 200).path("total").asInt()
        );
        assertEquals(1, get("/staff/reviewer-options?search=paged-reviewer", admin, 200).size());
        get("/staff/assignments/page", recruiter, 403);
        get("/staff/reviewer-options", recruiter, 403);
        get("/staff/assignments/" + first + "/processes", recruiter, 403);
        assertEquals(
            0,
            get("/staff/assignments/" + first + "/processes", admin, 200)
                .path("items")
                .size()
        );
    }

    @Test
    void reviewerOnlySeesAssignedSubmissionsIncludingDirectLinks() throws Exception {
        String recruiterId = post(
            "/staff/accounts",
            admin,
            create("r@example.com", "RECRUITER"),
            200
        )
            .get("id")
            .asText();
        String recruiter = login("r@example.com");
        String reviewerId = post("/staff/accounts", admin, create("v@example.com", "REVIEWER"), 200)
            .get("id")
            .asText();
        String reviewer = login("v@example.com");
        post("/staff/accounts", admin, create("other@example.com", "REVIEWER"), 200);
        String other = login("other@example.com");
        String job = post(
            "/recruiter/interviews",
            recruiter,
            Map.of(
                "title",
                "Java role",
                "description",
                "Role",
                "durationMinutes",
                30,
                "scheduledAt",
                0,
                "questionIds",
                List.of("q1")
            ),
            200
        )
            .get("id")
            .asText();
        db.update(
            "INSERT INTO sessions (id,owner,state,consent,consent_at,checks,questions,evidence_consent,interview_id,submitted_at) VALUES ('assigned','owner','SUBMITTED',TRUE,0,'[]','[]',TRUE,?,1)",
            job
        );
        get("/review/sessions/assigned", reviewer, 404);
        post("/staff/assignments/" + job, admin, Map.of("reviewerId", reviewerId), 200);
        var assignmentEvents = get("/staff/activity", admin, 200);
        var assignmentEvent = java.util.stream.StreamSupport.stream(
            assignmentEvents.spliterator(),
            false
        )
            .filter(e -> e.get("ACTION").asText().equals("REVIEWER_ASSIGNED"))
            .findFirst()
            .orElseThrow();
        assertEquals(
            "Assigned a reviewer to an interview.",
            assignmentEvent.get("ACTION_LABEL").asText()
        );
        assertTrue(assignmentEvent.get("TARGET_LABEL").asText().contains("Java role"));
        assertTrue(assignmentEvent.get("TARGET_LABEL").asText().contains("v@example.com"));
        assertEquals(1, get("/review/sessions", reviewer, 200).get("total").asInt());
        get("/review/sessions/assigned", reviewer, 200);
        get("/review/sessions/assigned", other, 404);
        assertEquals(0, get("/review/sessions", other, 200).get("total").asInt());
        db.update(
            "INSERT INTO integrity_evidence VALUES ('evidence','assigned','TAB_HIDDEN','SCREEN',0,?,'image/jpeg',?)",
            System.currentTimeMillis() + 60000,
            new byte[] { 1 }
        );
        get("/review/evidence/evidence", other, 404);
        post(
            "/review/sessions/assigned",
            reviewer,
            Map.of("score", 3, "notes", "Reviewed", "recommendation", "HOLD"),
            200
        );
        assertTrue(get("/staff/activity", admin, 200).toString().contains("REVIEW_SAVED"));
        post("/staff/assignments/" + job, admin, Map.of("reviewerId", ""), 200);
        assertTrue(
            get("/staff/activity", admin, 200)
                .toString()
                .contains("Removed the reviewer assignment.")
        );
        get("/review/sessions/assigned", reviewer, 404);
    }

    @Test
    void candidateLogoutRevokesOnlyCandidateToken() throws Exception {
        db.update(
            "INSERT INTO tokens (token,expires,role) VALUES (?,?,'CANDIDATE')",
            StaffAccess.hash("candidate-logout"),
            System.currentTimeMillis() + 60000
        );
        get("/questions", "candidate-logout", 200);
        post("/auth/logout", "candidate-logout", Map.of(), 200);
        get("/questions", "candidate-logout", 401);
        post("/auth/logout", admin, Map.of(), 401);
        get("/staff/me", admin, 200);
    }

    @Test
    void activityHandlesLegacyAndUnavailableReferences() throws Exception {
        db.update(
            "INSERT INTO access_activity VALUES ('old','legacy','REVIEWER_ASSIGNED','missing-interview',1)"
        );
        db.update(
            "INSERT INTO access_activity VALUES ('unknown','legacy','FUTURE_EVENT','missing-target',2)"
        );
        db.update(
            "INSERT INTO access_activity VALUES ('account','legacy','ACCOUNT_CREATED','missing-account',3)"
        );
        String activity = get("/staff/activity", admin, 200).toString();
        assertTrue(activity.contains("Interview no longer available"));
        assertTrue(activity.contains("Account no longer available"));
        assertTrue(activity.contains("Recorded an activity."));
        assertTrue(activity.contains("Legacy reviewer"));
    }

    @Test
    void templateApprovalAndCandidateReviewAssignmentsAreIndependent() throws Exception {
        String version = "react-developer-mid-v1";
        String aId = post("/staff/accounts", admin, create("approver@example.com", "REVIEWER"), 200)
            .get("id")
            .asText();
        String a = login("approver@example.com");
        String bId = post("/staff/accounts", admin, create("scorer@example.com", "REVIEWER"), 200)
            .get("id")
            .asText();
        String b = login("scorer@example.com");
        post("/staff/accounts", admin, create("recruit@example.com", "RECRUITER"), 200);
        String recruiter = login("recruit@example.com");
        assertEquals(0, get("/review/template-queue", a, 200).get("total").asInt());
        get("/review/templates/" + version, a, 404);
        post("/review/templates/" + version + "/approve", a, Map.of(), 404);
        assertEquals(0, get("/recruiter/template-queue", recruiter, 200).get("total").asInt());
        post("/staff/template-assignments/" + version, admin, Map.of("reviewerId", aId), 200);
        assertEquals(1, get("/review/template-queue", a, 200).get("allCount").asInt());
        assertEquals(1, get("/review/templates", a, 200).size());
        assertEquals(0, get("/review/template-queue", b, 200).get("allCount").asInt());
        get("/review/templates/" + version, a, 200);
        post("/review/templates/" + version + "/approve", b, Map.of(), 404);
        post("/review/templates/" + version + "/approve", a, Map.of(), 200);
        assertEquals(1, get("/recruiter/templates", recruiter, 200).size());
        var queue = get("/recruiter/template-queue?status=DRAFT", recruiter, 200);
        assertEquals(1, queue.get("total").asInt());
        assertEquals(0, queue.get("pendingCount").asInt());
        String job = post(
            "/recruiter/templates/" + version + "/interviews",
            recruiter,
            Map.of("title", "Frontend hiring", "scheduledAt", 0),
            200
        )
            .get("id")
            .asText();
        db.update(
            "INSERT INTO sessions (id,owner,state,consent,consent_at,checks,questions,evidence_consent,interview_id,submitted_at) VALUES ('separate','owner','SUBMITTED',TRUE,0,'[]','[]',TRUE,?,1)",
            job
        );
        get("/review/sessions/separate", a, 404);
        post("/staff/assignments/" + job, admin, Map.of("reviewerId", bId), 200);
        get("/review/sessions/separate", b, 200);
        get("/review/templates/" + version, b, 404);
        post("/staff/template-assignments/" + version, admin, Map.of("reviewerId", bId), 200);
        get("/review/templates/" + version, a, 404);
        get("/review/templates/" + version, b, 200);
        get("/review/sessions/separate", b, 200);
        post("/staff/template-assignments/" + version, admin, Map.of("reviewerId", ""), 200);
        get("/review/templates/" + version, b, 404);
        get("/review/sessions/separate", b, 200);
        assertTrue(get("/staff/assignments", admin, 200).toString().contains("RECRUITER_NAME"));
    }

    @Test
    void deactivationRemovesTemplateAssignmentsButPreservesApprovedVersions() throws Exception {
        String id = post("/staff/accounts", admin, create("approved@example.com", "REVIEWER"), 200)
                .get("id")
                .asText(),
            token = login("approved@example.com"),
            version = "cloud-ops-mid-v1";
        post("/staff/template-assignments/" + version, admin, Map.of("reviewerId", id), 200);
        post("/review/templates/" + version + "/approve", token, Map.of(), 200);
        post("/staff/accounts/" + id, admin, Map.of("active", false, "role", "REVIEWER"), 200);
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM template_review_assignments WHERE reviewer_id=?",
                Integer.class,
                id
            )
        );
        assertEquals(
            "APPROVED",
            db.queryForObject(
                "SELECT status FROM assessment_templates WHERE version=?",
                String.class,
                version
            )
        );
        get("/review/templates/" + version, token, 401);
        post("/staff/template-assignments/" + version, admin, Map.of("reviewerId", id), 400);
    }

    @Test
    void recruiterSeesActualEvaluatorAfterReassignmentAndLegacyFallback() throws Exception {
        post("/staff/accounts", admin, create("evaluator-hire@example.com", "RECRUITER"), 200);
        String recruiter = login("evaluator-hire@example.com");
        String reviewerId = post(
                "/staff/accounts",
                admin,
                Map.of(
                    "name",
                    "Actual evaluator",
                    "email",
                    "actual@example.com",
                    "password",
                    "initial-password-123",
                    "role",
                    "REVIEWER"
                ),
                200
            )
                .get("id")
                .asText(),
            reviewer = login("actual@example.com");
        String newReviewer = post(
            "/staff/accounts",
            admin,
            Map.of(
                "name",
                "Current assignee",
                "email",
                "new@example.com",
                "password",
                "initial-password-123",
                "role",
                "REVIEWER"
            ),
            200
        )
            .get("id")
            .asText();
        String job = post(
            "/recruiter/interviews",
            recruiter,
            Map.of(
                "title",
                "Evaluator test",
                "description",
                "Role",
                "durationMinutes",
                30,
                "scheduledAt",
                0,
                "questionIds",
                List.of("q1")
            ),
            200
        )
            .get("id")
            .asText();
        String invitation = post(
            "/recruiter/interviews/" + job + "/invitations",
            recruiter,
            Map.of(
                "candidateName",
                "Candidate",
                "candidateEmail",
                "candidate@example.com",
                "expiresAt",
                System.currentTimeMillis() + 172800000
            ),
            200
        )
            .get("invitationId")
            .asText();
        db.update(
            "INSERT INTO sessions (id,owner,state,consent,consent_at,checks,questions,evidence_consent,interview_id,invitation_id,submitted_at) VALUES ('evaluated','owner','SUBMITTED',TRUE,0,'[]','[]',TRUE,?,?,1)",
            job,
            invitation
        );
        db.update("UPDATE invitations SET session_id='evaluated' WHERE id=?", invitation);
        var pending = get("/recruiter/submissions/evaluated/evaluation-summary", recruiter, 200);
        assertTrue(pending.get("evaluatorName").isNull());
        post("/staff/assignments/" + job, admin, Map.of("reviewerId", reviewerId), 200);
        post(
            "/review/sessions/evaluated",
            reviewer,
            Map.of("score", 4, "notes", "Reviewed", "recommendation", "SHORTLIST"),
            200
        );
        post("/staff/assignments/" + job, admin, Map.of("reviewerId", newReviewer), 200);
        var detail = get("/recruiter/submissions/evaluated/evaluation-summary", recruiter, 200);
        assertEquals("Actual evaluator", detail.get("evaluatorName").asText());
        assertEquals("actual@example.com", detail.get("evaluatorEmail").asText());
        assertTrue(detail.get("evaluatedAt").asLong() > 0);
        var listed = get("/recruiter/interviews/" + job + "/candidates", recruiter, 200);
        assertEquals("Actual evaluator", listed.get(0).get("evaluatorName").asText());
        db.update(
            "UPDATE reviews SET reviewer='old-shared-code-token' WHERE session_id='evaluated'"
        );
        var legacy = get("/recruiter/submissions/evaluated/evaluation-summary", recruiter, 200);
        assertEquals("Legacy reviewer (name not recorded)", legacy.get("evaluatorName").asText());
        assertTrue(legacy.get("evaluatorEmail").isNull());
    }
}
