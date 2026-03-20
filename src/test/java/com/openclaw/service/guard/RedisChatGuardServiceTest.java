package com.openclaw.service.guard;

import com.openclaw.common.exception.BusinessException;
import com.openclaw.service.guard.impl.RedisChatGuardService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RedisChatGuardServiceTest {

    @Test
    void shouldLimitRequestsInLocalMode() {
        RedisChatGuardService guardService = new RedisChatGuardService(
                null,
                true,
                false,
                2,
                60,
                30,
                30
        );

        guardService.checkRateLimit("s1");
        guardService.checkRateLimit("s1");
        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> guardService.checkRateLimit("s1"));

        Assertions.assertEquals(42901, ex.getCode());
    }

    @Test
    void shouldAcquireAndReleaseSessionLockInLocalMode() {
        RedisChatGuardService guardService = new RedisChatGuardService(
                null,
                true,
                false,
                20,
                60,
                30,
                30
        );

        String token = guardService.acquireSessionLock("s-lock");
        Assertions.assertNotNull(token);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> guardService.acquireSessionLock("s-lock"));
        Assertions.assertEquals(40901, ex.getCode());

        guardService.releaseSessionLock("s-lock", token);
        Assertions.assertDoesNotThrow(() -> guardService.acquireSessionLock("s-lock"));
    }
}
