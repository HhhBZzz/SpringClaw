package com.springclaw.web.auth;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 基于 Bearer Token 的轻量 HTTP 鉴权拦截器。
 */
@Component
public class TokenAuthenticationInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public TokenAuthenticationInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(40111, "当前接口需要登录后访问");
        }
        AuthService.UserIdentity identity = authService.authenticateToken(token);
        RequestUserContextHolder.set(new RequestUserContext(
                identity.username(),
                identity.roleCode(),
                identity.expireAt()
        ));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestUserContextHolder.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization)) {
            return "";
        }
        String text = authorization.trim();
        if (text.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return text.substring(7).trim();
        }
        return text;
    }
}
