package com.springclaw.service.security;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.security.impl.DefaultWebhookSecurityService;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

class DefaultWebhookSecurityServiceTest {

    @Test
    void shouldPassWhenSignatureIsValid() {
        DefaultWebhookSecurityService service = new DefaultWebhookSecurityService(
                null,
                true,
                false,
                300,
                "test-secret",
                "",
                "",
                ""
        );

        String body = "{\"k\":\"v\"}";
        long ts = Instant.now().getEpochSecond();
        String nonce = "nonce-1";
        String sign = sign("test-secret", ts + "\n" + nonce + "\n" + body);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Springclaw-Timestamp", String.valueOf(ts));
        headers.put("X-Springclaw-Nonce", nonce);
        headers.put("X-Springclaw-Signature", sign);

        Assertions.assertDoesNotThrow(() -> service.verify("telegram", headers, body));
    }

    @Test
    void shouldRejectReplayRequest() {
        DefaultWebhookSecurityService service = new DefaultWebhookSecurityService(
                null,
                true,
                false,
                300,
                "test-secret",
                "",
                "",
                ""
        );

        String body = "{\"k\":\"v\"}";
        long ts = Instant.now().getEpochSecond();
        String nonce = "nonce-replay";
        String sign = sign("test-secret", ts + "\n" + nonce + "\n" + body);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Springclaw-Timestamp", String.valueOf(ts));
        headers.put("X-Springclaw-Nonce", nonce);
        headers.put("X-Springclaw-Signature", sign);

        service.verify("wechat", headers, body);
        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> service.verify("wechat", headers, body));
        Assertions.assertEquals(40104, ex.getCode());
    }

    @Test
    void shouldRejectWhenSignatureInvalid() {
        DefaultWebhookSecurityService service = new DefaultWebhookSecurityService(
                null,
                true,
                false,
                300,
                "test-secret",
                "",
                "",
                ""
        );

        long ts = Instant.now().getEpochSecond();
        Map<String, String> headers = Map.of(
                "X-Springclaw-Timestamp", String.valueOf(ts),
                "X-Springclaw-Nonce", "nonce-x",
                "X-Springclaw-Signature", "bad-signature"
        );

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> service.verify("telegram", headers, "{}"));
        Assertions.assertEquals(40103, ex.getCode());
    }

    @Test
    void shouldKeepLocalNoncesForTwiceReplayWindowWithSizeBound() throws Exception {
        DefaultWebhookSecurityService service = new DefaultWebhookSecurityService(
                null,
                true,
                false,
                300,
                "test-secret",
                "",
                "",
                ""
        );

        Field field = DefaultWebhookSecurityService.class.getDeclaredField("localNonceCache");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Cache<String, Boolean> cache = (Cache<String, Boolean>) field.get(service);

        Assertions.assertEquals(10_000L, cache.policy().eviction().orElseThrow().getMaximum());
        Assertions.assertEquals(600L, cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter(TimeUnit.SECONDS));
    }

    private String sign(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
