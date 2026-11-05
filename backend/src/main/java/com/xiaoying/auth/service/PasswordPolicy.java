package com.xiaoying.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;
import com.xiaoying.common.error.FieldErrorDto;

/**
 * 密码哈希与校验。长度 8 ~ 64 字符由 DTO 的 Bean Validation 校验（OQ-24 临时默认）；
 * 这里额外处理 BCrypt 只接受 ≤ 72 字节输入的限制（多字节字符可能在 64 字符内超出）。
 */
@Component
public class PasswordPolicy {

    static final int BCRYPT_MAX_BYTES = 72;

    private final PasswordEncoder encoder;
    /** 用户不存在时也做一次 BCrypt 比较，使"用户不存在"与"密码错误"的耗时相近，不泄露账号是否存在。 */
    private final String dummyHash;

    public PasswordPolicy(PasswordEncoder encoder) {
        this.encoder = encoder;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    /** 用于设置新密码：超出 BCrypt 字节上限时返回 400 VALIDATION_FAILED。 */
    public String hashNewPassword(String field, String rawPassword) {
        if (tooLong(rawPassword)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null, Map.of("fieldErrors", List.of(
                    FieldErrorDto.of(field, "LENGTH", "密码过长，请减少中文等多字节字符"))));
        }
        return encoder.encode(rawPassword);
    }

    /** {@code passwordHash} 为 null（用户不存在）时与假哈希比较并返回 false。 */
    public boolean matches(String rawPassword, String passwordHash) {
        if (rawPassword == null || tooLong(rawPassword)) {
            encoder.matches("x", dummyHash);
            return false;
        }
        boolean ok = encoder.matches(rawPassword, passwordHash == null ? dummyHash : passwordHash);
        return ok && passwordHash != null;
    }

    private static boolean tooLong(String rawPassword) {
        return rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }
}
