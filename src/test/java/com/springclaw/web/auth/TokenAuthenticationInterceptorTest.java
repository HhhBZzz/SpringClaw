package com.springclaw.web.auth;

import com.springclaw.service.auth.AuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenAuthenticationInterceptorTest {

    @AfterEach
    void clearContext() {
        RequestUserContextHolder.clear();
    }

    @Test
    void shouldAuthenticateWithHttpOnlyCookieToken() {
        AuthService authService = mock(AuthService.class);
        when(authService.authenticateToken("cookie-token"))
                .thenReturn(new AuthService.UserIdentity("cookie_user", "USER", System.currentTimeMillis() + 60_000));
        TokenAuthenticationInterceptor interceptor = new TokenAuthenticationInterceptor(authService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(TokenAuthenticationInterceptor.AUTH_COOKIE_NAME, "cookie-token"));

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        Assertions.assertTrue(allowed);
        verify(authService).authenticateToken("cookie-token");
        RequestUserContext context = RequestUserContextHolder.get();
        assertNotNull(context);
        Assertions.assertEquals("cookie_user", context.username());
    }

    @Test
    void shouldRejectNonBearerAuthorizationHeaderWithoutCallingAuthService() {
        AuthService authService = mock(AuthService.class);
        TokenAuthenticationInterceptor interceptor = new TokenAuthenticationInterceptor(authService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic abc123");

        Assertions.assertThrows(RuntimeException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        verify(authService, never()).authenticateToken("Basic abc123");
    }
}
