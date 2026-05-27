package com.springclaw.service.auth;

import com.springclaw.service.auth.impl.AuthServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentMap;

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
                "springclaw:auth:token:"
        );

        AuthService.LoginSession register = service.register("test_user", "123456");
        Assertions.assertNotNull(register.token());
        Assertions.assertEquals("USER", register.roleCode());

        AuthService.LoginSession login = service.login("test_user", "123456");
        Assertions.assertNotNull(login.token());
        Assertions.assertEquals("USER", login.roleCode());
    }

    @Test
    void shouldStoreNewLocalPasswordsWithBcryptHashVersion() throws Exception {
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
                "springclaw:auth:token:"
        );

        service.register("bcrypt_user", "123456");

        String hash = localPasswordHash(service, "bcrypt_user");
        Assertions.assertTrue(hash.startsWith("v2:$2"), "new password hashes should use versioned BCrypt");
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
                "springclaw:auth:token:"
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
                "springclaw:auth:token:"
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
                "springclaw:auth:token:"
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
                "springclaw:auth:token:"
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

    @Test
    void shouldRejectTokenWhenLocalUserDisabled() throws Exception {
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
                "springclaw:auth:token:"
        );

        AuthService.LoginSession session = service.register("disabled_user", "123456");
        disableLocalUser(service, "disabled_user");

        Assertions.assertThrows(
                RuntimeException.class,
                () -> service.authenticateToken(session.token())
        );
        Assertions.assertTrue(service.listActiveSessions(10).isEmpty());
    }

    @Test
    void shouldRevokeLocalToken() {
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
                "springclaw:auth:token:"
        );

        AuthService.LoginSession session = service.register("logout_user", "123456");
        service.revokeToken(session.token());

        Assertions.assertThrows(RuntimeException.class, () -> service.authenticateToken(session.token()));
    }

    @SuppressWarnings("unchecked")
    private void disableLocalUser(AuthServiceImpl service, String username) throws Exception {
        Field localUsersField = AuthServiceImpl.class.getDeclaredField("localUsers");
        localUsersField.setAccessible(true);
        ConcurrentMap<String, Object> localUsers = (ConcurrentMap<String, Object>) localUsersField.get(service);
        Object localUser = localUsers.get(username);
        if (localUser == null) {
            throw new AssertionError("local user not found: " + username);
        }
        Method passwordHash = localUser.getClass().getDeclaredMethod("passwordHash");
        Method roleCode = localUser.getClass().getDeclaredMethod("roleCode");
        passwordHash.setAccessible(true);
        roleCode.setAccessible(true);
        String hash = (String) passwordHash.invoke(localUser);
        String role = (String) roleCode.invoke(localUser);

        Class<?> localUserClass = localUser.getClass();
        var constructor = localUserClass.getDeclaredConstructor(String.class, String.class, String.class);
        constructor.setAccessible(true);
        Object disabled = constructor.newInstance(hash, role, "DISABLED");
        localUsers.put(username, disabled);
    }

    @SuppressWarnings("unchecked")
    private String localPasswordHash(AuthServiceImpl service, String username) throws Exception {
        Field localUsersField = AuthServiceImpl.class.getDeclaredField("localUsers");
        localUsersField.setAccessible(true);
        ConcurrentMap<String, Object> localUsers = (ConcurrentMap<String, Object>) localUsersField.get(service);
        Object localUser = localUsers.get(username);
        Method passwordHash = localUser.getClass().getDeclaredMethod("passwordHash");
        passwordHash.setAccessible(true);
        return (String) passwordHash.invoke(localUser);
    }
}
