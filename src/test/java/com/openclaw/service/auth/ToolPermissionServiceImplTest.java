package com.openclaw.service.auth;

import com.openclaw.common.exception.BusinessException;
import com.openclaw.service.auth.impl.ToolPermissionServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ToolPermissionServiceImplTest {

    @Test
    void shouldDenyRunCommandForUserByDefault() {
        ToolPermissionServiceImpl service = new ToolPermissionServiceImpl(
                null,
                new FixedAuthService("USER"),
                true,
                false,
                false,
                "SystemToolPack.runCommand",
                "SystemToolPack.now,SystemToolPack.uuid"
        );

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> service.checkPermission("u1", "SystemToolPack.runCommand"));
        Assertions.assertEquals(40311, ex.getCode());
    }

    @Test
    void shouldAllowNowForGuestByDefault() {
        ToolPermissionServiceImpl service = new ToolPermissionServiceImpl(
                null,
                new FixedAuthService("GUEST"),
                true,
                false,
                false,
                "SystemToolPack.runCommand",
                "SystemToolPack.now,SystemToolPack.uuid"
        );

        Assertions.assertDoesNotThrow(() -> service.checkPermission("u1", "SystemToolPack.now"));
    }

    private record FixedAuthService(String roleCode) implements AuthService {

        @Override
        public LoginSession register(String username, String rawPassword) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoginSession login(String username, String rawPassword) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserIdentity authenticateToken(String token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String resolveRoleByUserId(String userId) {
            return roleCode;
        }

        @Override
        public TokenRuntimeStats tokenRuntimeStats() {
            return new TokenRuntimeStats(0, 0, 0, 0, 0, false, 0);
        }

        @Override
        public List<ActiveSession> listActiveSessions(int limit) {
            return List.of();
        }
    }
}
