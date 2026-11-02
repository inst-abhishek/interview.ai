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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:authoring-tests;DB_CLOSE_DELAY=-1",
        "interview.admin-setup-code=author-admin",
        "interview.ai-enabled=false",
        "logging.level.root=ERROR"
    }
)
@AutoConfigureMockMvc
@Transactional
class TemplateAuthoringTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate db;

    @MockBean
    ChatGptConnection chatgpt;

    @MockBean
    AiTemplateGenerator ai;

    @MockBean
    JavaCodeRunner runner;

    String admin, author, approver, recruiter, authorId, approverId;

    JsonNode post(String path, String token, Object body, int expected) throws Exception {
        var request = MockMvcRequestBuilders.post("/api" + path)
            .contentType("application/json")
            .content(json.writeValueAsString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        String data = mvc
            .perform(request)
            .andExpect(status().is(expected))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return data.isBlank() ? json.createObjectNode() : json.readTree(data);
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

    String createAccount(String role, String email) throws Exception {
        return post(
            "/staff/accounts",
            admin,
            Map.of("name", role, "email", email, "password", "author-password-123", "role", role),
            200
        )
            .get("id")
            .asText();
    }

    String login(String email) throws Exception {
        return post(
            "/staff/login",
            null,
            Map.of("email", email, "password", "author-password-123"),
            200
        )
            .get("token")
            .asText();
    }

    @BeforeEach
    void setup() throws Exception {
        when(ai.model()).thenReturn("test-model");
        when(ai.available()).thenReturn(true);
        admin = post(
            "/staff/bootstrap",
            null,
            Map.of(
                "name",
                "Admin",
                "email",
                "admin@author.test",
                "password",
                "author-password-123",
                "setupCode",
                "author-admin"
            ),
            200
        )
            .get("token")
            .asText();
        authorId = createAccount("REVIEWER", "author@example.com");
        approverId = createAccount("REVIEWER", "approver@example.com");
        createAccount("RECRUITER", "hire@example.com");
        post(
            "/staff/accounts/" + authorId + "/author-permission",
            admin,
            Map.of("enabled", true),
            200
        );
        author = login("author@example.com");
        approver = login("approver@example.com");
        recruiter = login("hire@example.com");
    }

    TemplateCatalogue.Definition definition(String version) {
        return new TemplateCatalogue.Definition(
            version,
            "React Developer",
            "Scenario assessment",
            60,
            List.of("React"),
            "Review the submitted response against the rubric.",
            List.of(
                new TemplateCatalogue.Item(
                    "q1",
                    "TECHNICAL",
                    "Explain how you prevent stale request results.",
                    List.of(),
                    "",
                    "",
                    100,
                    List.of(
                        new TemplateCatalogue.Criterion(
                            "r1",
                            "Request correctness",
                            100,
                            "0: no solution. 50: partial handling. 100: cancellation and stale result protection."
                        )
                    ),
                    "",
                    List.of(),
                    List.of(),
                    ""
                )
            )
        );
    }

    JsonNode draft(String version) throws Exception {
        return post(
            "/author/drafts",
            author,
            Map.of("version", version, "title", "React Developer"),
            200
        );
    }

    JsonNode save(String version, int revision, TemplateCatalogue.Definition d) throws Exception {
        return post(
            "/author/drafts/" + version,
            author,
            Map.of("revision", revision, "definition", d),
            200
        );
    }

    void ready(String version) throws Exception {
        draft(version);
        save(version, 0, definition(version));
        post("/author/drafts/" + version + "/validate", author, Map.of("revision", 1), 200);
    }

    @Test
    void manualDraftApprovalAndVersioningPreserveApprovedContent() throws Exception {
        String v = "react-author-v1";
        ready(v);
        post(
            "/author/drafts/" + v + "/submit",
            author,
            Map.of("revision", 1, "approverId", approverId),
            200
        );
        assertEquals(0, get("/recruiter/templates", recruiter, 200).size());
        get("/review/templates/" + v, approver, 200);
        post("/review/templates/" + v + "/approve", approver, Map.of(), 200);
        assertEquals(1, get("/recruiter/templates", recruiter, 200).size());
        String original = db.queryForObject(
            "SELECT definition FROM assessment_templates WHERE version=?",
            String.class,
            v
        );
        var copy = post(
            "/author/drafts",
            author,
            Map.of("version", "react-author-v2", "title", "React Senior", "parentVersion", v),
            200
        );
        assertEquals(1, copy.get("definition").get("items").size());
        assertEquals("react-author-v2", copy.get("definition").get("version").asText());
        assertEquals(
            original,
            db.queryForObject(
                "SELECT definition FROM assessment_templates WHERE version=?",
                String.class,
                v
            )
        );
        post(
            "/author/drafts/" + v,
            author,
            Map.of("revision", 2, "definition", definition(v)),
            409
        );
    }

    @Test
    void ownershipPermissionAndIndependentApprovalAreEnforced() throws Exception {
        draft("private-draft-v1");
        get("/author/drafts", approver, 403);
        get("/author/status", recruiter, 403);
        post(
            "/staff/accounts/" + approverId + "/author-permission",
            admin,
            Map.of("enabled", true),
            200
        );
        String other = login("approver@example.com");
        get("/author/drafts/private-draft-v1", other, 404);
        save("private-draft-v1", 0, definition("private-draft-v1"));
        post("/author/drafts/private-draft-v1/validate", author, Map.of("revision", 1), 200);
        post(
            "/author/drafts/private-draft-v1/submit",
            author,
            Map.of("revision", 1, "approverId", authorId),
            400
        );
        post(
            "/author/drafts/private-draft-v1/submit",
            author,
            Map.of("revision", 1, "approverId", approverId),
            200
        );
        post(
            "/staff/template-assignments/private-draft-v1",
            admin,
            Map.of("reviewerId", authorId),
            400
        );
        db.update(
            "UPDATE template_review_assignments SET reviewer_id=? WHERE version='private-draft-v1'",
            authorId
        );
        post("/review/templates/private-draft-v1/approve", author, Map.of(), 403);
        post(
            "/staff/accounts/" + authorId + "/author-permission",
            admin,
            Map.of("enabled", false),
            200
        );
        get("/author/drafts", author, 401);
        String revoked = login("author@example.com");
        get("/author/drafts", revoked, 403);
    }

    @Test
    void staleSavesAndValidationInvalidationPreventPublishingChangedContent() throws Exception {
        String v = "revision-test-v1";
        ready(v);
        post(
            "/author/drafts/" + v,
            author,
            Map.of("revision", 0, "definition", definition(v)),
            409
        );
        save(v, 1, definition(v));
        post(
            "/author/drafts/" + v + "/submit",
            author,
            Map.of("revision", 2, "approverId", approverId),
            409
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM assessment_templates WHERE version=?",
                Integer.class,
                v
            )
        );
    }

    @Test
    void approverCanReturnDraftForCorrectionAndAuthorCanResubmit() throws Exception {
        String v = "returned-v1";
        ready(v);
        post(
            "/author/drafts/" + v + "/submit",
            author,
            Map.of("revision", 1, "approverId", approverId),
            200
        );
        post(
            "/author/templates/" + v + "/return",
            approver,
            Map.of("reason", "Clarify partial credit guidance."),
            200
        );
        var returned = get("/author/drafts/" + v, author, 200);
        assertEquals("DRAFT", returned.get("state").asText());
        assertTrue(returned.get("feedback").asText().contains("partial credit"));
        get("/review/templates/" + v, approver, 404);
        post(
            "/author/drafts/" + v + "/validate",
            author,
            Map.of("revision", returned.get("revision").asInt()),
            200
        );
        post(
            "/author/drafts/" + v + "/submit",
            author,
            Map.of("revision", returned.get("revision").asInt(), "approverId", approverId),
            200
        );
    }

    @Test
    void aiDraftIsRecordedAsUnapprovedAndGenerationFailurePreservesSavedContent() throws Exception {
        String v = "ai-draft-v1";
        draft(v);
        when(ai.generate(eq(v), any())).thenReturn(definition(v));
        var brief = Map.of(
            "role",
            "React Developer",
            "level",
            "Mid-level",
            "skills",
            List.of("React"),
            "durationMinutes",
            60,
            "mcqCount",
            0,
            "writtenCount",
            1,
            "codingCount",
            0,
            "requirements",
            "Request cancellation"
        );
        var generated = post(
            "/author/drafts/" + v + "/generate",
            author,
            Map.of("revision", 0, "brief", brief),
            200
        );
        assertEquals("test-model", generated.get("aiModel").asText());
        assertFalse(generated.get("validated").asBoolean());
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM assessment_templates WHERE version=?",
                Integer.class,
                v
            )
        );
        post(
            "/author/drafts/" + v + "/generate",
            author,
            Map.of("revision", 1, "brief", brief),
            429
        );
        verify(ai, times(1)).generate(eq(v), any());
    }

    @Test
    void failedReferenceTestsCannotValidateCodingDraft() throws Exception {
        String v = "java-reference-v1";
        draft(v);
        var d = new TemplateCatalogue.Definition(
            v,
            "Java Backend",
            "Java assessment",
            60,
            List.of("Java"),
            "Review code and tests",
            List.of(
                new TemplateCatalogue.Item(
                    "q1",
                    "CODING",
                    "Print the sum of two integers",
                    List.of(),
                    "",
                    "",
                    100,
                    List.of(
                        new TemplateCatalogue.Criterion("r1", "Quality", 10, "0-10 for clarity")
                    ),
                    "public class Main {}",
                    List.of(new TemplateCatalogue.TestCase("p1", "1 2", "3")),
                    List.of(new TemplateCatalogue.TestCase("h1", "4 5", "9")),
                    "public class Main {}"
                )
            )
        );
        save(v, 0, d);
        when(runner.enabled()).thenReturn(true);
        when(runner.run(anyString(), anyList())).thenReturn(
            new JavaCodeRunner.Result(
                "COMPLETE",
                0,
                2,
                List.of(new JavaCodeRunner.TestResult("p1", "WRONG_ANSWER", "1 2", "3", "4", 1)),
                "Evaluation completed"
            )
        );
        var failure = post("/author/drafts/" + v + "/validate", author, Map.of("revision", 1), 400);
        assertTrue(failure.path("message").asText().contains("0 of 2 tests"));
        assertEquals("q1", failure.path("validation").path("questionId").asText());
        assertEquals("4", failure.path("validation").path("tests").get(0).path("output").asText());
        assertFalse(
            get("/author/drafts/" + v, author, 200)
                .get("validated")
                .asBoolean()
        );
        post(
            "/author/drafts/" + v + "/submit",
            author,
            Map.of("revision", 1, "approverId", approverId),
            409
        );
    }

    @Test
    void invalidRubricCannotPassValidation() throws Exception {
        String v = "invalid-rubric-v1";
        draft(v);
        var base = definition(v);
        var invalid = new TemplateCatalogue.Definition(
            v,
            base.title(),
            base.description(),
            60,
            base.skills(),
            base.reviewerInstructions(),
            List.of(
                new TemplateCatalogue.Item(
                    "q1",
                    "TECHNICAL",
                    "Question",
                    List.of(),
                    "",
                    "",
                    100,
                    List.of(new TemplateCatalogue.Criterion("r1", "Only partial", 10, "Guide")),
                    "",
                    List.of(),
                    List.of(),
                    ""
                )
            )
        );
        save(v, 0, invalid);
        post("/author/drafts/" + v + "/validate", author, Map.of("revision", 1), 400);
    }

    @Test
    void providerFailureDoesNotReplaceSavedDraft() throws Exception {
        String v = "failed-ai-v1";
        draft(v);
        save(v, 0, definition(v));
        String original = db.queryForObject(
            "SELECT definition FROM template_drafts WHERE version=?",
            String.class,
            v
        );
        when(ai.generate(eq(v), any())).thenThrow(
            new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI unavailable")
        );
        post(
            "/author/drafts/" + v + "/generate",
            author,
            Map.of("revision", 1, "brief", Map.of()),
            502
        );
        assertEquals(
            original,
            db.queryForObject(
                "SELECT definition FROM template_drafts WHERE version=?",
                String.class,
                v
            )
        );
    }

    @Test
    void draftChangedDuringGenerationIsNotOverwritten() throws Exception {
        String v = "concurrent-ai-v1";
        draft(v);
        String original = db.queryForObject(
            "SELECT definition FROM template_drafts WHERE version=?",
            String.class,
            v
        );
        when(ai.generate(eq(v), any())).thenAnswer(call -> {
            db.update("UPDATE template_drafts SET revision=revision+1 WHERE version=?", v);
            return definition(v);
        });
        post(
            "/author/drafts/" + v + "/generate",
            author,
            Map.of("revision", 0, "brief", Map.of()),
            409
        );
        assertEquals(
            original,
            db.queryForObject(
                "SELECT definition FROM template_drafts WHERE version=?",
                String.class,
                v
            )
        );
    }

    @Test
    void subscriptionGenerationUsesOnlyTheAuthorsConnectionAndNeverFallsBackToPaidApi()
        throws Exception {
        String v = "subscription-draft-v1";
        draft(v);
        when(chatgpt.models(authorId)).thenReturn(
            List.of(new ChatGptConnection.Model("plan-model", "Plan model"))
        );
        when(chatgpt.access(authorId)).thenReturn("private-plan-token");
        when(
            ai.generateWithChatGpt(eq(v), any(), eq("private-plan-token"), eq("plan-model"))
        ).thenReturn(definition(v));
        var result = post(
            "/author/drafts/" + v + "/generate",
            author,
            Map.of("revision", 0, "brief", Map.of(), "provider", "chatgpt", "model", "plan-model"),
            200
        );
        assertEquals("ChatGPT / plan-model", result.path("aiModel").asText());
        verify(ai, never()).generate(anyString(), any());
        verify(chatgpt).access(authorId);
    }

    @Test
    void unavailableSubscriptionModelPreservesDraftWithoutCallingEitherProvider() throws Exception {
        String v = "unavailable-plan-v1";
        draft(v);
        when(chatgpt.models(authorId)).thenReturn(
            List.of(new ChatGptConnection.Model("allowed", "Allowed"))
        );
        post(
            "/author/drafts/" + v + "/generate",
            author,
            Map.of("revision", 0, "brief", Map.of(), "provider", "chatgpt", "model", "not-allowed"),
            400
        );
        assertEquals(
            0,
            get("/author/drafts/" + v, author, 200)
                .path("revision")
                .asInt()
        );
        verify(ai, never()).generate(anyString(), any());
        verify(ai, never()).generateWithChatGpt(anyString(), any(), anyString(), anyString());
    }
}
