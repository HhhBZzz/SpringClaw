package com.springclaw.web.auth;

/**
 * 当前线程的认证用户上下文。
 */
public final class RequestUserContextHolder {

    private static final ThreadLocal<RequestUserContext> HOLDER = new ThreadLocal<>();

    private RequestUserContextHolder() {
    }

    public static RequestUserContext get() {
        return HOLDER.get();
    }

    public static void set(RequestUserContext context) {
        HOLDER.set(context);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
