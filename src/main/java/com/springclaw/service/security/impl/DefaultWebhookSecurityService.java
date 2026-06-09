package com.springclaw.service.security.impl;

import com.springclaw.common.util.TextUtils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.security.WebhookSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 默认 Webhook 安全校验实现。
 *
 * 协议定义：
 * - Header: X-Openclaw-Timestamp (epoch seconds)
 * - Header: X-Openclaw-Nonce
 * - Header: X-Openclaw-Signature (hex hmac-sha256)
 * - SignText: timestamp + "\\n" + nonce + "\\n" + rawBody
 */
@Service
public class DefaultWebhookSecurityService implements WebhookSecurityService {

    private static final Logger log = LoggerFactory.getLogger(DefaultWebhookSecurityService.class);
    private static final long LOCAL_NONCE_CACHE_MAX_SIZE = 10_000L;

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final boolean redisEnabled;
    private final long replaySeconds;
    private final String defaultSecret;
    private final String telegramSecret;
    private final String wechatSecret;
    private final String feishuSecret;

    private final Cache<String, Boolean> localNonceCache;

    public DefaultWebhookSecurityService(@Autowired(required = false) StringRedisTemplate redisTemplate,
                                         @Value("${springclaw.webhook.security.enabled:false}") boolean enabled,
                                         @Value("${springclaw.webhook.security.redis-enabled:true}") boolean redisEnabled,
                                         @Value("${springclaw.webhook.security.replay-seconds:300}") long replaySeconds,
                                         @Value("${springclaw.webhook.security.default-secret:}") String defaultSecret,
                                         @Value("${springclaw.webhook.security.channel-secrets.telegram:}") String telegramSecret,
                                         @Value("${springclaw.webhook.security.channel-secrets.wechat:}") String wechatSecret,
                                         @Value("${springclaw.webhook.security.channel-secrets.feishu:}") String feishuSecret) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.redisEnabled = redisEnabled;
        this.replaySeconds = Math.max(30L, replaySeconds);
        this.defaultSecret = defaultSecret;
        this.telegramSecret = telegramSecret;
        this.wechatSecret = wechatSecret;
        this.feishuSecret = feishuSecret;
        this.localNonceCache = Caffeine.newBuilder()
                .maximumSize(LOCAL_NONCE_CACHE_MAX_SIZE)
                .expireAfterWrite(2 * this.replaySeconds, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void verify(String channel, Map<String, String> headers, String rawBody) {
        if (!enabled) {
            return;
        }

        String timestamp = header(headers, "x-springclaw-timestamp");
        String nonce = header(headers, "x-springclaw-nonce");
        String signature = header(headers, "x-springclaw-signature");

        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
            throw new BusinessException(40101, "Webhook 鉴权头缺失");
        }

        long ts = parseEpochSeconds(timestamp);
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > replaySeconds) {
            throw new BusinessException(40102, "Webhook 请求已过期");
        }

        String secret = resolveSecret(channel);
        if (!StringUtils.hasText(secret)) {
            throw new BusinessException(50011, "Webhook 安全已开启但未配置 secret");
        }

        String safeBody = rawBody == null ? "" : rawBody;
        String signText = ts + "\n" + nonce + "\n" + safeBody;
        String expected = hmacSha256Hex(secret, signText);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(40103, "Webhook 签名校验失败");
        }

        ensureNotReplayed(channel, ts, nonce);
    }

    private void ensureNotReplayed(String channel, long ts, String nonce) {
        String ch = TextUtils.normalize(channel);
        String nonceKey = "springclaw:webhook:nonce:" + (ch.isEmpty() ? "unknown" : ch) + ":" + ts + ":" + nonce;

        if (redisEnabled && redisTemplate != null) {
            try {
                Boolean ok = redisTemplate.opsForValue()
                        .setIfAbsent(nonceKey, "1", Duration.ofSeconds(replaySeconds));
                if (Boolean.FALSE.equals(ok)) {
                    throw new BusinessException(40104, "Webhook 重放请求");
                }
                return;
            } catch (BusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                log.warn("Redis 防重放失败，降级本地防重放。key={}, reason={}", nonceKey, ex.getMessage());
            }
        }

        if (localNonceCache.asMap().putIfAbsent(nonceKey, Boolean.TRUE) != null) {
            throw new BusinessException(40104, "Webhook 重放请求");
        }
    }

    private String resolveSecret(String channel) {
        String ch = TextUtils.normalize(channel);
        String lower = ch.isEmpty() ? "unknown" : ch;
        if ("telegram".equals(lower) && StringUtils.hasText(telegramSecret)) {
            return telegramSecret;
        }
        if ("wechat".equals(lower) && StringUtils.hasText(wechatSecret)) {
            return wechatSecret;
        }
        if ("feishu".equals(lower) && StringUtils.hasText(feishuSecret)) {
            return feishuSecret;
        }
        return defaultSecret;
    }

    private long parseEpochSeconds(String value) {
        try {
            long ts = Long.parseLong(value.trim());
            if (ts > 1_000_000_000_000L) {
                return ts / 1000L;
            }
            return ts;
        } catch (NumberFormatException ex) {
            throw new BusinessException(40105, "Webhook 时间戳格式非法");
        }
    }

    private String header(Map<String, String> headers, String lowerName) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && lowerName.equals(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }
        return "";
    }

    private String hmacSha256Hex(String secret, String content) {
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
            throw new BusinessException(50012, "Webhook 签名计算失败: " + ex.getMessage());
        }
    }

}
