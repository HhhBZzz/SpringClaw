package com.openclaw.web.auth;

import com.openclaw.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 基于 {@link RequireRole} 的角色校验拦截器。
 */
@Component
public class RoleAuthorizationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole requireRole = findAnnotation(handlerMethod);
        if (requireRole == null) {
            return true;
        }
        RequestUserContext context = RequestUserContextHolder.get();
        if (context == null) {
            throw new BusinessException(40112, "当前接口需要登录后访问");
        }
        if (!context.hasAnyRole(requireRole.value())) {
            throw new BusinessException(40312, "当前账号无权访问该接口");
        }
        return true;
    }

    private RequireRole findAnnotation(HandlerMethod handlerMethod) {
        RequireRole methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequireRole.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequireRole.class);
    }
}
