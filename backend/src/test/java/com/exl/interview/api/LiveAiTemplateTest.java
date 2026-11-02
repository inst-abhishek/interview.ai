package com.exl.interview.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Opt-in only: uses paid API access and synthetic role specifications, never candidate data. */
@EnabledIfEnvironmentVariable(named = "INTERVIEW_LIVE_AI_TEST", matches = "true")
@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:live-author-smoke;DB_CLOSE_DELAY=-1",
        "interview.ai-enabled=true",
        "logging.level.root=ERROR"
    }
)
class LiveAiTemplateTest {

    @Autowired
    AiTemplateGenerator generator;

    @Autowired
    TemplateValidator validator;

    @Test
    void generatesSmallSyntheticAssessment() {
        assertTrue(
            generator.available(),
            "Configure the backend-only key before running this opt-in test"
        );
        var draft = generator.generate(
            "live-react-smoke-v1",
            new AiTemplateGenerator.Brief(
                "React Developer",
                "Mid-level",
                List.of("React state", "HTTP request handling"),
                30,
                1,
                1,
                0,
                "Use one MCQ worth 40 points and one written question worth 60 points. Focus on React state updates and preventing stale request results. Use one written criterion worth 60 points with detailed point bands."
            )
        );
        assertEquals(2, draft.items().size());
        assertEquals("live-react-smoke-v1", draft.version());
        assertTrue(
            validator.errors(draft).isEmpty(),
            "Generated draft did not pass structural validation"
        );
    }
}
