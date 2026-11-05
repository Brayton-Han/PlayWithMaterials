package com.xiaoying.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLIntegrityConstraintViolationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.xiaoying.auth.domain.User;
import com.xiaoying.auth.mapper.UserMapper;
import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;

/** 并发注册时预检查通过、唯一索引兜底的分支，以及登录失败不签发会话。 */
class AuthServiceTest {

    private UserMapper userMapper;
    private SessionService sessionService;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        sessionService = mock(SessionService.class);
        service = new AuthService(userMapper, sessionService, new PasswordPolicy(new BCryptPasswordEncoder(4)));
    }

    @Test
    void uniqueUsernameViolationRaceMapsToUsernameTaken() {
        raceOn("Duplicate entry 'alice' for key 'users.uk_users_username'");
        assertThatThrownBy(() -> service.register("alice", "a@example.com", "password1", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.USERNAME_TAKEN));
        verify(sessionService, never()).create(anyLong());
    }

    @Test
    void uniqueEmailViolationRaceMapsToEmailTaken() {
        raceOn("Duplicate entry 'a@example.com' for key 'users.uk_users_active_email'");
        assertThatThrownBy(() -> service.register("alice", "a@example.com", "password1", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_TAKEN));
    }

    @Test
    void otherDuplicateKeyIsRethrown() {
        raceOn("Duplicate entry 'x' for key 'users.PRIMARY'");
        assertThatThrownBy(() -> service.register("alice", "a@example.com", "password1", null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void unknownUserLoginIsInvalidCredentialsWithoutSession() {
        assertThatThrownBy(() -> service.login("ghost", "password1", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        verify(sessionService, never()).create(anyLong());
    }

    @Test
    void successfulLoginRevokesPreviousSessionAndIssuesNewOne() {
        User user = new User("alice", "a@example.com", new BCryptPasswordEncoder(4).encode("password1"), null);
        user.setId(5L);
        when(userMapper.findActiveByUsername("alice")).thenReturn(user);
        when(sessionService.create(5L)).thenReturn("new-token");

        AuthService.Authenticated result = service.login("alice", "password1", "old-token");

        assertThat(result.token()).isEqualTo("new-token");
        verify(sessionService).revoke("old-token");
    }

    private void raceOn(String mysqlMessage) {
        when(userMapper.existsByUsername(anyString())).thenReturn(false);
        when(userMapper.existsActiveEmail(anyString())).thenReturn(false);
        doThrow(new DuplicateKeyException("insert failed", new SQLIntegrityConstraintViolationException(mysqlMessage)))
                .when(userMapper).insert(any(User.class));
    }
}
