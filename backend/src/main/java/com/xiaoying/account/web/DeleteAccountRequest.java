package com.xiaoying.account.web;

import jakarta.validation.constraints.NotNull;

/**
 * api.yaml DeleteAccountRequest。缺失为 400 VALIDATION_FAILED；
 * 值是否完全等于 {@code DELETE} 由 {@code AccountService} 判断（400 CONFIRM_TEXT_MISMATCH）。
 */
public record DeleteAccountRequest(
        @NotNull(message = "请输入 DELETE 以确认删除")
        String confirmText) {
}
