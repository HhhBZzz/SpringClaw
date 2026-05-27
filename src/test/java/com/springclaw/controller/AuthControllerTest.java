package com.springclaw.controller;

import com.springclaw.mapper.UserAccountMapper;
import com.springclaw.service.auth.AuthService;
import com.springclaw.web.auth.TokenAuthenticationInterceptor;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void shouldWriteSecureAuthCookieWhenConfigured() {
        AuthService authService = mock(AuthService.class);
        when(authService.login("secure_user", "123456"))
                .thenReturn(new AuthService.LoginSession("secure-token", "secure_user", "USER", System.currentTimeMillis() + 60_000));
        AuthController controller = new AuthController(authService, mock(UserAccountMapper.class), true, false, false, true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.login(new com.springclaw.dto.auth.AuthLoginRequest("secure_user", "123456"), response);

        Cookie cookie = response.getCookie(TokenAuthenticationInterceptor.AUTH_COOKIE_NAME);
        Assertions.assertNotNull(cookie);
        Assertions.assertTrue(cookie.getSecure());
        Assertions.assertTrue(cookie.isHttpOnly());
    }

    @Test
    void shouldRevokeCookieTokenAndClearCookieWithMatchingSecureFlagOnLogout() {
        AuthService authService = mock(AuthService.class);
        AuthController controller = new AuthController(authService, mock(UserAccountMapper.class), true, false, false, true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(TokenAuthenticationInterceptor.AUTH_COOKIE_NAME, "cookie-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout(null, null, request, response);

        verify(authService).revokeToken("cookie-token");
        Cookie cookie = response.getCookie(TokenAuthenticationInterceptor.AUTH_COOKIE_NAME);
        Assertions.assertNotNull(cookie);
        Assertions.assertTrue(cookie.getSecure());
        Assertions.assertTrue(cookie.isHttpOnly());
        Assertions.assertEquals(0, cookie.getMaxAge());
    }
}
