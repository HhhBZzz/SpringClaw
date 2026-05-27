package com.springclaw.service.session.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.springclaw.domain.entity.AgentSession;
import com.springclaw.mapper.AgentSessionMapper;
import com.springclaw.service.session.AgentSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话服务实现。
 *
 * 设计说明：
 * 1. “查不到就创建”放在 Service 层，防止 Controller 直接操控持久化细节。
 * 2. 这是典型的 MVC 分层边界：Controller 编排请求，Service 编排业务状态。
 */
@Service
public class AgentSessionServiceImpl extends ServiceImpl<AgentSessionMapper, AgentSession>
        implements AgentSessionService {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionServiceImpl.class);

     /**
     * 数据库异常时的本地降级存储，保障接口可用性。
     */
    private final Cache<String, AgentSession> localSessionCache;
    private final AtomicLong localIdGenerator = new AtomicLong(1);
    private final boolean dbEnabled;
    /**
     * 数据库短路窗口，避免数据库不可用时每个请求都重试连接造成阻塞。
     */
    private static final long DB_RETRY_INTERVAL_MS = 30_000L;
    private volatile long dbRetryAt = 0L;

    public AgentSessionServiceImpl(@Value("${springclaw.persistence.db-enabled:false}") boolean dbEnabled,
                                   @Value("${springclaw.session.local-cache-max-size:5000}") long localCacheMaxSize,
                                   @Value("${springclaw.session.local-cache-ttl-hours:24}") long localCacheTtlHours) {
        this.dbEnabled = dbEnabled;
        this.localSessionCache = Caffeine.newBuilder()
                .maximumSize(Math.max(100, localCacheMaxSize))
                .expireAfterAccess(Math.max(1, localCacheTtlHours), TimeUnit.HOURS)
                .build();
    }

    @Override
    public AgentSession getOrCreate(String sessionKey, String channel, String userId) {
        if (!dbEnabled) {
            return getOrCreateFromLocal(sessionKey, channel, userId);
        }

        if (isDbTemporarilyUnavailable()) {
            return getOrCreateFromLocal(sessionKey, channel, userId);
        }

        try {
            AgentSession session = lambdaQuery()
                    .eq(AgentSession::getSessionKey, sessionKey)
                    .one();
            if (session != null) {
                return session;
            }

            AgentSession newSession = buildSession(sessionKey, channel, userId);
            save(newSession);
            return newSession;
        } catch (Exception ex) {
            markDbTemporarilyUnavailable(ex);
            return getOrCreateFromLocal(sessionKey, channel, userId);
        }
    }

    @Override
    public void persistConversation(AgentSession session, String userMessage, String assistantMessage, String soulVersion) {
        session.setLastUserMessage(userMessage);
        session.setLastAssistantMessage(assistantMessage);
        session.setSoulVersion(soulVersion);
        session.setStatus("ACTIVE");

        if (!dbEnabled) {
            localSessionCache.put(session.getSessionKey(), session);
            return;
        }

        if (session.getId() != null && session.getId() > 0) {
            if (isDbTemporarilyUnavailable()) {
                localSessionCache.put(session.getSessionKey(), session);
                return;
            }
            try {
                updateById(session);
                return;
            } catch (Exception ex) {
                markDbTemporarilyUnavailable(ex);
            }
        }
        localSessionCache.put(session.getSessionKey(), session);
    }

    private AgentSession buildSession(String sessionKey, String channel, String userId) {
        AgentSession session = new AgentSession();
        session.setSessionKey(sessionKey);
        session.setChannel(channel);
        session.setUserId(userId);
        session.setStatus("ACTIVE");
        return session;
    }

    private AgentSession getOrCreateFromLocal(String sessionKey, String channel, String userId) {
        return localSessionCache.get(sessionKey, key -> {
            AgentSession local = buildSession(sessionKey, channel, userId);
            local.setId(-localIdGenerator.getAndIncrement());
            return local;
        });
    }

    private boolean isDbTemporarilyUnavailable() {
        return System.currentTimeMillis() < dbRetryAt;
    }

    private void markDbTemporarilyUnavailable(Exception ex) {
        dbRetryAt = System.currentTimeMillis() + DB_RETRY_INTERVAL_MS;
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        log.warn("数据库不可用，{}ms 内走内存降级。reason={}", DB_RETRY_INTERVAL_MS, reason);
    }
}
