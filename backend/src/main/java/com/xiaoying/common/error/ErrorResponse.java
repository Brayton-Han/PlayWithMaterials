package com.xiaoying.common.error;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应体：{@code { "code": "...", "message": "...", "details": { ... } }}（api.yaml ErrorResponse）。
 * {@code details} 为空时不输出。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(ErrorCode code, String message, Map<String, Object> details) {

    public static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(code, code.defaultMessage(), null);
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code, message, null);
    }
}
