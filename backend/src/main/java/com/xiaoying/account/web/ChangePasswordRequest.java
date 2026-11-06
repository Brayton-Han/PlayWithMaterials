package com.xiaoying.account.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** api.yaml ChangePasswordRequest。"确认新密码"只在前端校验，接口不接收（DQ-04）；新密码规则同注册（OQ-24）。 */
public record ChangePasswordRequest(
        @NotNull(message = "请输入当前密码")
        @Size(min = 1, max = 64, message = "请输入当前密码")
        String currentPassword,

        @NotNull(message = "请输入新密码")
        @Size(min = 8, max = 64, message = "密码需为 8 ~ 64 位")
        String newPassword) {
}
