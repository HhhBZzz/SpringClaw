package com.springclaw.service.guard;

/**
 * 会话防护服务（限流 + 并发锁）。
 */
public interface ChatGuardService {

    void checkRateLimit(String sessionKey);

    String acquireSessionLock(String sessionKey);

    void releaseSessionLock(String sessionKey, String token);
}
