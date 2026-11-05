package com.xiaoying.auth.security;

import java.time.Duration;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.xiaoying.auth.AuthProperties;

/**
 * 会话 Cookie {@code sid}（api.yaml SetSessionCookie / ClearSessionCookie）：
 * {@code HttpOnly; SameSite=Lax; Path=/; Max-Age=604800}，{@code Secure} 由 {@code app.auth.cookie-secure} 控制。
 */
@Component
public class SessionCookies {

    public static final String NAME = "sid";

    private final AuthProperties properties;

    public SessionCookies(AuthProperties properties) {
        this.properties = properties;
    }

    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public String issue(String token) {
        return build(token, properties.sessionTtl());
    }

    public String clear() {
        return build("", Duration.ZERO);
    }

    private String build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
