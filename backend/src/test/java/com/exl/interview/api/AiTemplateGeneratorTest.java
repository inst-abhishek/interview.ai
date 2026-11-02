package com.exl.interview.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class AiTemplateGeneratorTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void extractsStructuredOutputAndHandlesRefusalAndIncompleteResponses() throws Exception {
        var ai = new AiTemplateGenerator(json);
        String complete = json.writeValueAsString(
            Map.of(
                "status",
                "completed",
                "output",
                List.of(
                    Map.of(
                        "type",
                        "message",
                        "content",
                        List.of(Map.of("type", "output_text", "text", "{\"title\":\"Draft\"}"))
                    )
                )
            )
        );
        assertEquals("Draft", ai.extract(complete).get("title").asText());
        assertThrows(ResponseStatusException.class, () ->
            ai.extract("{\"status\":\"incomplete\",\"output\":[]}")
        );
        assertThrows(ResponseStatusException.class, () ->
            ai.extract(
                "{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"refusal\",\"refusal\":\"No\"}]}]}"
            )
        );
    }

    @Test
    void disabledOrMissingKeyDoesNotMakeAnApiCallOrExposeCredentials() {
        var ai = new AiTemplateGenerator(json);
        ReflectionTestUtils.setField(ai, "enabled", false);
        ReflectionTestUtils.setField(ai, "key", "secret-test-key");
        var error = assertThrows(ResponseStatusException.class, () -> ai.generate("demo-v1", null));
        assertEquals(503, error.getStatusCode().value());
        assertFalse(error.getReason().contains("secret-test-key"));
    }

    @Test
    void nonJavaCodingIsRejectedBeforeAnyProviderCall() {
        var ai = new AiTemplateGenerator(json);
        ReflectionTestUtils.setField(ai, "enabled", true);
        ReflectionTestUtils.setField(ai, "key", "secret-test-key");
        var brief = new AiTemplateGenerator.Brief(
            "React Developer",
            "Mid-level",
            List.of("React"),
            60,
            1,
            1,
            1,
            ""
        );
        assertEquals(
            400,
            assertThrows(ResponseStatusException.class, () -> ai.generate("react-v1", brief))
                .getStatusCode()
                .value()
        );
    }

    @Test
    void subscriptionStreamRequiresCompletedEventAndNeverTreatsPartialOutputAsSuccess()
        throws Exception {
        var ai = new AiTemplateGenerator(json);
        var response = Map.of(
            "status",
            "completed",
            "output",
            List.of(
                Map.of(
                    "content",
                    List.of(Map.of("type", "output_text", "text", "{\"title\":\"Draft\"}"))
                )
            )
        );
        assertEquals(
            "Draft",
            ai
                .extractStream(
                    "data: " +
                        json.writeValueAsString(
                            Map.of("type", "response.completed", "response", response)
                        ) +
                        "\n\n"
                )
                .path("title")
                .asText()
        );
        assertThrows(ResponseStatusException.class, () ->
            ai.extractStream(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
            )
        );
        var limit = assertThrows(ResponseStatusException.class, () ->
            ai.extractStream(
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"
            )
        );
        assertEquals(429, limit.getStatusCode().value());
    }
}
