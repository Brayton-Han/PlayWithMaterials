package com.xiaoying.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.xiaoying.auth.AuthProperties;
import com.xiaoying.auth.domain.User;
import com.xiaoying.auth.mapper.SessionMapper;
import com.xiaoying.auth.security.CurrentUser;

/**
 * 服务端会话（architecture §4.1）：32 字节 SecureRandom → Base64URL 令牌下发给浏览器，
 * 数据库只保存其 SHA-256 哈希；固定有效期，不续期 [OQ-28]。
 */
@Service
public class SessionService {

    private static final int TOKEN_BYTES = 32;
    /** 合法令牌为 43 个字符；明显超长的 Cookie 直接视为无效，不查库。 */
    private static final int MAX_TOKEN_LENGTH = 128;

    private final SessionMapper sessionMapper;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    public SessionService(SessionMapper sessionMapper, AuthProperties properties) {
        this.sessionMapper = sessionMapper;
        this.properties = properties;
    }

    /** 创建会话并返回明文令牌（只用于 Set-Cookie，不落库、不记日志）。 */
    public String create(long userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = now();
        sessionMapper.insert(hash(token), userId, now, now.plus(properties.sessionTtl()));
        return token;
    }

    public Optional<CurrentUser> resolve(String token) {
        if (!isPlausible(token)) {
            return Optional.empty();
        }
        User user = sessionMapper.findActiveUserByTokenHash(hash(token), now());
        return Optional.ofNullable(user).map(u -> new CurrentUser(u.getId(), u.getUsername()));
    }

    /** 删除会话；令牌为空或不存在时什么也不做（logout 幂等）。 */
    public void revoke(String token) {
        if (isPlausible(token)) {
            sessionMapper.deleteByTokenHash(hash(token));
        }
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static boolean isPlausible(String token) {
        return token != null && !token.isBlank() && token.length() <= MAX_TOKEN_LENGTH;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
