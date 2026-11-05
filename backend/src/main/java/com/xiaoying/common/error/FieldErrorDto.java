package com.xiaoying.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 字段级错误（api.yaml FieldError），放在 {@code details.fieldErrors[]} 中。
 * {@code slotIndex} 仅文件错误时出现，从 0 开始。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldErrorDto(String field, Integer slotIndex, String code, String message) {

    public static FieldErrorDto of(String field, String code, String message) {
        return new FieldErrorDto(field, null, code, message);
    }
}
