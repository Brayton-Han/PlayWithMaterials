package com.xiaoying.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.auth.*}（architecture §7）。
 *
 * @param sessionTtl   会话固定有效期，不续期 [OQ-28 临时默认 7 天]
 * @param cookieSecure 会话 Cookie 是否带 {@code Secure}（本地 http 为 false）
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(Duration sessionTtl, boolean cookieSecure) {

    public AuthProperties {
        if (sessionTtl == null) {
            sessionTtl = Duration.ofDays(7);
        }
    }
}
