package com.exl.interview.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

class LiveInterviewAiTest {

    private final ChatGptConnection connection = mock(ChatGptConnection.class);
    private final StaffAccess staff = mock(StaffAccess.class);
    private final LiveInterviewAi ai = new LiveInterviewAi(
        new ObjectMapper(),
        mock(AiTemplateGenerator.class),
        connection,
        staff
    );
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void setup() {
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        doAnswer(invocation -> {
            if (!"127.0.0.1".equals(invocation.getArgument(0))) throw ChatGptConnection.fail(
                403,
                "Local only"
            );
            return null;
        })
            .when(connection)
            .requireLocal(anyString());
        when(connection.status("author")).thenReturn(
            new ChatGptConnection.ConnectionStatus(true, true, "test@example.com", "")
        );
        when(connection.models("author")).thenReturn(
            List.of(new ChatGptConnection.Model("demo-model", "Demo model"))
        );
        when(staff.authenticate("session", "REVIEWER")).thenReturn(
            new StaffAccess.Account("author", "Author", "test@example.com", "REVIEWER", true, true)
        );
    }

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void subscriptionNeedsExplicitEnableAndDisconnectDisablesAvailability() {
        assertFalse(ai.available());
        ai.configureDemo("author", "session", true, "demo-model");
        assertTrue(ai.available());
        when(connection.status("author")).thenReturn(
            new ChatGptConnection.ConnectionStatus(true, false, "", "")
        );
        assertFalse(ai.available());
        verify(connection, never()).access(anyString());
    }

    @Test
    void remoteRequestsAndRevokedSessionCannotUseDemo() {
        ai.configureDemo("author", "session", true, "demo-model");
        request.setRemoteAddr("192.168.1.20");
        assertFalse(ai.available());
        request.setRemoteAddr("127.0.0.1");
        when(staff.authenticate("session", "REVIEWER")).thenThrow(
            ChatGptConnection.fail(401, "Signed out")
        );
        assertFalse(ai.available());
    }

    @Test
    void invalidModelAndDifferentOwnerCannotReplaceDemo() {
        assertThrows(ResponseStatusException.class, () ->
            ai.configureDemo("author", "session", true, "unknown")
        );
        ai.configureDemo("author", "session", true, "demo-model");
        assertThrows(ResponseStatusException.class, () ->
            ai.configureDemo("other", "other-session", true, "demo-model")
        );
        ai.configureDemo("other", "other-session", false, "");
        assertTrue(ai.demoEnabled("author"));
        ai.configureDemo("author", "session", false, "");
        assertFalse(ai.available());
    }
}
