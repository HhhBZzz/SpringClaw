package com.springclaw.common.response;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一返回体。
 *
 * 设计说明：
 * 1. 校招面试中，统一返回结构是“接口治理”的基础能力点。
 * 2. 统一 code/message/data 后，前端和网关可以稳定解析，不会因接口风格差异增加对接成本。
 */
public class ApiResponse<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int code;
    private final String message;
    private final T data;

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "OK", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(0, message, data);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
