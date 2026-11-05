package com.xiaoying.auth.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** api.yaml RegisterRequest。"确认密码"只在前端校验，接口不接收（DQ-04）。 */
public record RegisterRequest(
        @NotNull(message = "请输入用户名")
        @Pattern(regexp = "^[A-Za-z0-9_]{3,20}$", message = "用户名需为 3 ~ 20 位字母、数字或下划线")
        String username,

        @NotBlank(message = "请输入邮箱")
        @Size(max = 254, message = "邮箱最长 254 位")
        @Email(message = "邮箱格式不正确")
        String email,

        @NotNull(message = "请输入密码")
        @Size(min = 8, max = 64, message = "密码需为 8 ~ 64 位")
        String password) {
}
