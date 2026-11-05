package com.xiaoying.auth.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.xiaoying.auth.domain.User;
import com.xiaoying.auth.mapper.UserMapper;
import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;

/** 注册、登录、退出、当前用户（F-12）。 */
@Service
public class AuthService {

    /** 登录 / 注册成功：用户 + 新会话的明文令牌（只用于 Set-Cookie）。 */
    public record Authenticated(User user, String token) {
    }

    private final UserMapper userMapper;
    private final SessionService sessionService;
    private final PasswordPolicy passwordPolicy;

    public AuthService(UserMapper userMapper, SessionService sessionService, PasswordPolicy passwordPolicy) {
        this.userMapper = userMapper;
        this.sessionService = sessionService;
        this.passwordPolicy = passwordPolicy;
    }

    /**
     * 注册并登录。先查重给出明确提示；并发注册时由唯一索引兜底（DuplicateKeyException → 409）。
     * {@code previousToken} 为调用方当前的会话令牌（可空），成功后作废，即"替换当前会话"。
     */
    @Transactional
    public Authenticated register(String username, String email, String password, String previousToken) {
        if (userMapper.existsByUsername(username)) {
            throw new ApiException(ErrorCode.USERNAME_TAKEN);
        }
        if (userMapper.existsActiveEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }
        String hash = passwordPolicy.hashNewPassword("password", password);
        User user = new User(username, email, hash, Instant.now().truncatedTo(ChronoUnit.MILLIS));
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw duplicateToApiException(e);
        }
        return startSession(user, previousToken);
    }

    /** 用户名 + 密码登录。用户不存在、密码错误、账户已删除都返回同一个 401。 */
    @Transactional
    public Authenticated login(String username, String password, String previousToken) {
        User user = userMapper.findActiveByUsername(username);
        if (!passwordPolicy.matches(password, user == null ? null : user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return startSession(user, previousToken);
    }

    public void logout(String token) {
        sessionService.revoke(token);
    }

    public User currentUser(long userId) {
        User user = userMapper.findActiveById(userId);
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        return user;
    }

    private Authenticated startSession(User user, String previousToken) {
        sessionService.revoke(previousToken);
        return new Authenticated(user, sessionService.create(user.getId()));
    }

    /** 按 MySQL 报错中的索引名区分（"Duplicate entry '...' for key 'users.uk_users_username'"）。 */
    static ApiException duplicateToApiException(DuplicateKeyException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("uk_users_active_email")) {
            return new ApiException(ErrorCode.EMAIL_TAKEN);
        }
        if (message.contains("uk_users_username")) {
            return new ApiException(ErrorCode.USERNAME_TAKEN);
        }
        throw e;
    }
}
