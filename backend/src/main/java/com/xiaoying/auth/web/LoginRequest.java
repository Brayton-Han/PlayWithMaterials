package com.xiaoying.auth.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** api.yaml LoginRequest。只按长度做基本校验，不暴露注册规则。 */
public record LoginRequest(
        @NotNull(message = "请输入用户名")
        @Size(min = 1, max = 20, message = "请输入用户名")
        String username,

        @NotNull(message = "请输入密码")
        @Size(min = 1, max = 64, message = "请输入密码")
        String password) {
}
