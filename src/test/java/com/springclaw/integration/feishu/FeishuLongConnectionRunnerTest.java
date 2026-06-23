package com.springclaw.integration.feishu;

import com.springclaw.service.webhook.WebhookRouterService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FeishuLongConnectionRunnerTest {

    @Test
    void sdkCallbackPayloadUsesTrustedDispatch() {
        WebhookRouterService router = mock(WebhookRouterService.class);
        FeishuLongConnectionRunner runner = new FeishuLongConnectionRunner(
                router,
                null,
                "app-id",
                "app-secret",
                "",
                "",
                "",
                120
        );
        Map<String, Object> payload = Map.of("event", "group");

        runner.dispatchVerifiedPayload(payload);

        verify(router).dispatchTrusted("feishu", payload);
        runner.destroy();
    }
}
