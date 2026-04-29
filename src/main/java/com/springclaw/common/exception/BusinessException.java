package com.springclaw.common.exception;

/**
 * 业务异常。
 *
 * 设计说明：
 * 1. 将“业务可预期错误”和“系统异常”分层处理。
 * 2. 便于在全局异常处理器中统一映射成标准错误码。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
