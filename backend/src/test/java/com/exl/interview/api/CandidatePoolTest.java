package com.exl.interview.api;

import static org.junit.jupiter.api.Assertions.*;
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
        "spring.datasource.url=jdbc:h2:mem:bulk-invitations;DB_CLOSE_DELAY=-1",
        "interview.recruiter-setup-code=bulk-setup",
        "interview.public-app-url=https://assessment.example.test/",
        "interview.invitation-key-file=./target/bulk-invitation-test.key",
        "logging.level.root=ERROR"
    }
)
@AutoConfigureMockMvc
@Transactional
class CandidatePoolTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate db;

    @Autowired
    InvitationDelivery delivery;

    @MockBean
    InvitationMailer mailer;

    String recruiter, other, job;

    JsonNode post(String path, String token, Object body, int status) throws Exception {
        var req = MockMvcRequestBuilders.post("/api" + path)
            .contentType("application/json")
            .content(json.writeValueAsString(body));
        if (token != null) req.header("Authorization", "Bearer " + token);
        String data = mvc
            .perform(req)
            .andExpect(status().is(status))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return data.isBlank() ? json.createObjectNode() : json.readTree(data);
    }

    JsonNode get(String path, String token, int status) throws Exception {
        var req = MockMvcRequestBuilders.get("/api" + path);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return json.readTree(
            mvc
                .perform(req)
                .andExpect(status().is(status))
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
    }

    String account() throws Exception {
        return post(
            "/recruiter/register",
            null,
            Map.of(
                "name",
                "Hiring manager",
                "email",
                UUID.randomUUID() + "@example.com",
                "password",
                "private-password-123",
                "setupCode",
                "bulk-setup"
            ),
            200
        )
            .get("token")
            .asText();
    }

    JsonNode candidate(String token, String name, String email) throws Exception {
        return post(
            "/recruiter/pool",
            token,
            Map.of("name", name, "email", email, "skills", "Java, Angular", "experience", 4),
            200
        );
    }

    @BeforeEach
    void init() throws Exception {
        when(mailer.available()).thenReturn(false);
        recruiter = account();
        other = account();
        job = post(
            "/recruiter/interviews",
            recruiter,
            Map.of(
                "title",
                "Java role",
                "description",
                "Backend assessment",
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
    }

    Map<String, Object> bulk(List<String> ids, String key, boolean email) {
        return Map.of(
            "interviewId",
            job,
            "candidateIds",
            ids,
            "expiresAt",
            System.currentTimeMillis() + 172800000,
            "sendEmail",
            email,
            "requestKey",
            key
        );
    }

    @Test
    void poolIsPrivateValidatedAndPaginated() throws Exception {
        String id = candidate(recruiter, "One", "ONE@example.com").get("id").asText();
        candidate(other, "Other", "one@example.com");
        assertEquals(1, get("/recruiter/pool", other, 200).get("total").asInt());
        post(
            "/recruiter/pool/" + id,
            other,
            Map.of("name", "Steal", "email", "steal@example.com"),
            404
        );
        post(
            "/recruiter/pool",
            recruiter,
            Map.of("name", "Duplicate", "email", "one@example.com"),
            409
        );
        post(
            "/recruiter/pool",
            recruiter,
            Map.of("name", "Invalid", "email", "bad", "experience", -1),
            400
        );
        for (int i = 0; i < 12; i++) candidate(
            recruiter,
            "Candidate " + i,
            "candidate" + i + "@example.com"
        );
        var page = get(
            "/recruiter/pool?page=1&size=10&skill=Angular&minExperience=3",
            recruiter,
            200
        );
        assertEquals(13, page.get("total").asInt());
        assertEquals(3, page.get("items").size());
        assertEquals(0, get("/recruiter/pool?search=absent", recruiter, 200).get("total").asInt());
        get("/recruiter/pool?size=999", recruiter, 400);
        get("/recruiter/pool", null, 401);
    }

    @Test
    void csvPreviewHandlesQuotesDuplicatesAndInvalidRowsThenRevalidatesImport() throws Exception {
        candidate(recruiter, "Existing", "existing@example.com");
        String csv =
            "\uFEFFname,email,skills,experience\r\n\"Doe, Jane\",jane@example.com,\"Java, Angular\",3\r\n\"Line\nName\",line@example.com,Java,2\r\nDuplicate,JANE@example.com,React,2\r\nExisting,existing@example.com,AWS,2\r\nInvalid,bad,AWS,-1\r\n";
        var preview = post("/recruiter/pool/import-preview", recruiter, Map.of("csv", csv), 200);
        assertEquals(2, preview.get("ready").asInt());
        assertEquals(2, preview.get("duplicates").asInt());
        assertEquals(1, preview.get("invalid").asInt());
        assertEquals("Doe, Jane", preview.get("rows").get(0).get("name").asText());
        var imported = post("/recruiter/pool/import", recruiter, Map.of("csv", csv), 200);
        assertEquals(2, imported.get("imported").asInt());
        assertEquals(
            0,
            post("/recruiter/pool/import", recruiter, Map.of("csv", csv), 200)
                .get("imported")
                .asInt()
        );
        assertEquals(3, get("/recruiter/pool", recruiter, 200).get("total").asInt());
        post(
            "/recruiter/pool/import-preview",
            recruiter,
            Map.of("csv", "name,email\n\"unclosed,email@example.com"),
            400
        );
        post("/recruiter/pool/import-preview", recruiter, Map.of("csv", "email,email\na,b"), 400);
    }

    @Test
    void bulkRequestsAreIdempotentSkipExistingAndKeepInvitationLinks() throws Exception {
        String first = candidate(recruiter, "Already invited", "first@example.com")
                .get("id")
                .asText(),
            second = candidate(recruiter, "New candidate", "second@example.com").get("id").asText();
        var original = post(
            "/recruiter/interviews/" + job + "/invitations",
            recruiter,
            Map.of(
                "candidateName",
                "Already invited",
                "candidateEmail",
                "first@example.com",
                "expiresAt",
                System.currentTimeMillis() + 172800000
            ),
            200
        );
        var input = bulk(List.of(first, second), "batch-request-123", false);
        var preview = post("/recruiter/pool/bulk-preview", recruiter, input, 200);
        assertEquals(1, preview.get("ready").asInt());
        assertEquals(1, preview.get("alreadyInvited").asInt());
        var batch = post("/recruiter/pool/bulk-invitations", recruiter, input, 200);
        assertEquals(1, batch.get("created").asInt());
        assertEquals(1, batch.get("skipped").asInt());
        assertEquals(
            batch.get("id"),
            post("/recruiter/pool/bulk-invitations", recruiter, input, 200).get("id")
        );
        assertEquals(
            2,
            db.queryForObject(
                "SELECT COUNT(*) FROM invitations WHERE interview_id=?",
                Integer.class,
                job
            )
        );
        assertTrue(
            batch
                .get("items")
                .get(0)
                .get("url")
                .asText()
                .startsWith("https://assessment.example.test/#invite=")
        );
        get("/recruiter/pool/batches/" + batch.get("id").asText(), other, 404);
        var recovered = get(
            "/recruiter/invitations/" + original.get("invitationId").asText(),
            recruiter,
            200
        );
        assertEquals(original.get("code"), recovered.get("code"));
        post(
            "/recruiter/pool/" + second,
            recruiter,
            Map.of("name", "Updated candidate", "email", "changed@example.com"),
            200
        );
        assertEquals(
            0,
            post(
                "/recruiter/pool/bulk-preview",
                recruiter,
                bulk(List.of(second), "changed-email-123", false),
                200
            )
                .get("ready")
                .asInt()
        );
        Map<String, Object> changed = new HashMap<>(input);
        changed.put("candidateIds", List.of(second));
        post("/recruiter/pool/bulk-invitations", recruiter, changed, 409);
    }

    @Test
    void bulkValidationRejectsForeignCandidatesAndExportsSafeCsv() throws Exception {
        String foreign = candidate(other, "Foreign", "foreign@example.com").get("id").asText();
        post(
            "/recruiter/pool/bulk-invitations",
            recruiter,
            bulk(List.of(foreign), "foreign-123", false),
            404
        );
        assertEquals(
            0,
            db.queryForObject("SELECT COUNT(*) FROM invitation_batches", Integer.class)
        );
        String local = candidate(recruiter, "=SUM(1)", "local@example.com").get("id").asText();
        var input = bulk(List.of(local), "export-123", false);
        var batch = post("/recruiter/pool/bulk-invitations", recruiter, input, 200);
        String id = batch.get("id").asText();
        String csv = mvc
            .perform(
                MockMvcRequestBuilders.get("/api/recruiter/pool/batches/" + id + "/export").header(
                    "Authorization",
                    "Bearer " + recruiter
                )
            )
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertTrue(csv.contains("'=SUM(1)"));
        assertTrue(csv.contains("#invite="));
        Map<String, Object> expired = new HashMap<>(input);
        expired.put("requestKey", "expired-123");
        expired.put("expiresAt", System.currentTimeMillis());
        post("/recruiter/pool/bulk-invitations", recruiter, expired, 400);
    }

    @Test
    void emailIsOptionalAndSuccessfulSendingReusesInvitation() throws Exception {
        String candidate = candidate(recruiter, "Send candidate", "send@example.com")
            .get("id")
            .asText();
        post(
            "/recruiter/pool/bulk-preview",
            recruiter,
            bulk(List.of(candidate), "disabled-123", true),
            503
        );
        when(mailer.available()).thenReturn(true);
        var batch = post(
            "/recruiter/pool/bulk-invitations",
            recruiter,
            bulk(List.of(candidate), "mail-123", true),
            200
        );
        String id = batch.get("items").get(0).get("invitationId").asText();
        assertEquals("QUEUED", batch.get("items").get(0).get("deliveryStatus").asText());
        delivery.dispatchOne(id);
        assertEquals(
            "SENT",
            db.queryForObject(
                "SELECT status FROM invitation_delivery WHERE invitation_id=?",
                String.class,
                id
            )
        );
        verify(mailer).send(
            eq("send@example.com"),
            eq("Send candidate"),
            eq("Java role"),
            eq(0L),
            anyLong(),
            anyString(),
            eq(false)
        );
        post("/recruiter/pool/invitations/" + id + "/send", recruiter, Map.of(), 409);
        post(
            "/recruiter/pool/invitations/" + id + "/send",
            recruiter,
            Map.of("reminder", true),
            200
        );
        delivery.dispatchOne(id);
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM invitations WHERE interview_id=?",
                Integer.class,
                job
            )
        );
    }

    @Test
    void uncertainEmailOutcomeRequiresAcknowledgementAndNeverCreatesAnotherLink() throws Exception {
        when(mailer.available()).thenReturn(true);
        doThrow(new RuntimeException("timeout with secret credentials"))
            .when(mailer)
            .send(
                anyString(),
                anyString(),
                anyString(),
                anyLong(),
                anyLong(),
                anyString(),
                anyBoolean()
            );
        String c = candidate(recruiter, "Retry candidate", "retry@example.com").get("id").asText();
        var batch = post(
            "/recruiter/pool/bulk-invitations",
            recruiter,
            bulk(List.of(c), "retry-123", true),
            200
        );
        String invitation = batch.get("items").get(0).get("invitationId").asText();
        delivery.dispatchOne(invitation);
        assertEquals(
            "UNKNOWN",
            db.queryForObject(
                "SELECT status FROM invitation_delivery WHERE invitation_id=?",
                String.class,
                invitation
            )
        );
        assertFalse(
            get("/recruiter/pool/batches/" + batch.get("id").asText(), recruiter, 200)
                .toString()
                .contains("secret credentials")
        );
        post("/recruiter/pool/invitations/" + invitation + "/send", recruiter, Map.of(), 409);
        post(
            "/recruiter/pool/invitations/" + invitation + "/send",
            recruiter,
            Map.of("acknowledgeUnknown", true),
            200
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM invitations WHERE interview_id=?",
                Integer.class,
                job
            )
        );
    }

    @Test
    void expiredInvitationsAreNotSentByWorker() throws Exception {
        String c = candidate(recruiter, "Expired", "expired@example.com").get("id").asText();
        var batch = post(
            "/recruiter/pool/bulk-invitations",
            recruiter,
            bulk(List.of(c), "expiry-123", false),
            200
        );
        String id = batch.get("items").get(0).get("invitationId").asText();
        db.update("UPDATE invitations SET expires_at=0 WHERE id=?", id);
        db.update("UPDATE invitation_delivery SET status='QUEUED' WHERE invitation_id=?", id);
        delivery.dispatchOne(id);
        assertEquals(
            "FAILED",
            db.queryForObject(
                "SELECT status FROM invitation_delivery WHERE invitation_id=?",
                String.class,
                id
            )
        );
        verify(mailer, never()).send(
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyLong(),
            anyString(),
            anyBoolean()
        );
    }
}
