package com.openclaw.service.auth;

import com.openclaw.service.auth.impl.AuthServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AuthServiceImplTest {

    @Test
    void shouldRegisterAndLoginWhenDbDisabled() {
        AuthServiceImpl service = new AuthServiceImpl(
                null,
                true,
                false,
                false,
                "USER",
                "USER",
                false,
                24,
                "",
                "openclaw:auth:token:"
        );

        AuthService.LoginSession register = service.register("test_user", "123456");
        Assertions.assertNotNull(register.token());
        Assertions.assertEquals("USER", register.roleCode());

        AuthService.LoginSession login = service.login("test_user", "123456");
        Assertions.assertNotNull(login.token());
        Assertions.assertEquals("USER", login.roleCode());
    }

    @Test
    void shouldResolveDefaultRoleForUnknownUser() {
        AuthServiceImpl service = new AuthServiceImpl(
                null,
                true,
                false,
                false,
                "USER",
                "USER",
                false,
                24,
                "",
                "openclaw:auth:token:"
        );
        Assertions.assertEquals("USER", service.resolveRoleByUserId("unknown_user"));
    }

    @Test
    void shouldBootstrapFirstLocalUserAsAdminOnlyOnce() {
        AuthServiceImpl service = new AuthServiceImpl(
                null,
                true,
                false,
                false,
                "USER",
                "USER",
                true,
                24,
                "",
                "openclaw:auth:token:"
        );

        AuthService.LoginSession first = service.register("first_user", "123456");
        AuthService.LoginSession second = service.register("second_user", "123456");

        Assertions.assertEquals("ADMIN", first.roleCode());
        Assertions.assertEquals("USER", second.roleCode());
    }

    @Test
    void shouldAllowFeishuStyleUserIdAsUsername() {
        AuthServiceImpl service = new AuthServiceImpl(
                null,
                true,
                false,
                false,
                "USER",
                "USER",
                false,
                24,
                "",
                "openclaw:auth:token:"
        );

        AuthService.LoginSession register = service.register("ou_ca6151727e0e94061de41ff2061f9012", "123456");

        Assertions.assertNotNull(register.token());
        Assertions.assertEquals("ou_ca6151727e0e94061de41ff2061f9012", register.username());
    }

    @Test
    void shouldReportLocalTokenStats() {
        AuthServiceImpl service = new AuthServiceImpl(
                null,
                true,
                false,
                false,
                "USER",
                "USER",
                false,
                24,
                "",
                "openclaw:auth:token:"
        );

        service.register("user_a", "123456");
        service.login("user_a", "123456");
        service.register("user_b", "123456");

        AuthService.TokenRuntimeStats stats = service.tokenRuntimeStats();

        Assertions.assertEquals(3, stats.activeTokenCount());
        Assertions.assertEquals(2, stats.activeUserCount());
        Assertions.assertEquals(0, stats.redisTokenCount());
        Assertions.assertEquals(3, stats.localTokenCount());
        Assertions.assertEquals(24 * 3600L, stats.tokenTtlSeconds());
        Assertions.assertFalse(stats.redisBacked());
        Assertions.assertFalse(service.listActiveSessions(10).isEmpty());
    }
}
