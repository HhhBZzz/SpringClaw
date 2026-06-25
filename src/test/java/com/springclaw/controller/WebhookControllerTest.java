package com.springclaw.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.security.WebhookSecurityService;
import com.springclaw.service.webhook.WebhookRouterService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookControllerTest {

    @Test
    void disabledSecurityUsesUntrustedDispatch() {
        WebhookRouterService router = mock(WebhookRouterService.class);
        WebhookSecurityService security = mock(WebhookSecurityService.class);
        when(security.verify("feishu", Map.of(), "{\"event\":\"group\"}"))
                .thenReturn(false);
        WebhookController controller = new WebhookController(
                router,
                security,
                new ObjectMapper()
        );

        controller.inbound("feishu", Map.of(), "{\"event\":\"group\"}");

        verify(router).dispatch("feishu", Map.of("event", "group"));
        verify(router, never()).dispatchTrusted(
                eq("feishu"),
                eq(Map.of("event", "group"))
        );
    }

    @Test
    void successfulVerificationUsesTrustedDispatch() {
        WebhookRouterService router = mock(WebhookRouterService.class);
        WebhookSecurityService security = mock(WebhookSecurityService.class);
        when(security.verify("feishu", Map.of(), "{\"event\":\"group\"}"))
                .thenReturn(true);
        WebhookController controller = new WebhookController(
                router,
                security,
                new ObjectMapper()
        );

        controller.inbound("feishu", Map.of(), "{\"event\":\"group\"}");

        verify(router).dispatchTrusted("feishu", Map.of("event", "group"));
        verify(router, never()).dispatch(
                eq("feishu"),
                eq(Map.of("event", "group"))
        );
    }
}
