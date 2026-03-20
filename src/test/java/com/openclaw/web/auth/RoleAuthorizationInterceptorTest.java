package com.openclaw.web.auth;

import com.openclaw.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class RoleAuthorizationInterceptorTest {

    private final RoleAuthorizationInterceptor interceptor = new RoleAuthorizationInterceptor();

    @AfterEach
    void clearContext() {
        RequestUserContextHolder.clear();
    }

    @Test
    void shouldAllowAdminOnAnnotatedHandler() throws Exception {
        RequestUserContextHolder.set(new RequestUserContext("admin_local", "ADMIN", System.currentTimeMillis() + 60_000));

        boolean allowed = interceptor.preHandle(request(), response(), handler("adminOnly"));

        Assertions.assertTrue(allowed);
    }

    @Test
    void shouldRejectUserWithoutRequiredRole() {
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request(), response(), handler("adminOnly")));

        Assertions.assertEquals(40312, ex.getCode());
    }

    @Test
    void shouldRejectAnonymousOnAnnotatedHandler() {
        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request(), response(), handler("adminOnly")));

        Assertions.assertEquals(40112, ex.getCode());
    }

    private HandlerMethod handler(String methodName) throws NoSuchMethodException {
        return new HandlerMethod(new TestController(), TestController.class.getMethod(methodName));
    }

    private HttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    private HttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    @RequireRole({"ADMIN", "DEVELOPER"})
    private static final class TestController {

        public void adminOnly() {
        }
    }
}
