package com.xiaoying.account.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.xiaoying.auth.domain.User;
import com.xiaoying.auth.mapper.UserMapper;
import com.xiaoying.auth.service.PasswordPolicy;
import com.xiaoying.auth.service.SessionService;
import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;
import com.xiaoying.task.service.TaskAbandonService;

/** 账户设置（F-28）：修改密码、删除账户。只操作当前登录用户。 */
@Service
public class AccountService {

    /** [OQ-26 余项临时默认] 完全一致：区分大小写、不去除首尾空格。 */
    static final String DELETE_CONFIRM_TEXT = "DELETE";

    private final UserMapper userMapper;
    private final SessionService sessionService;
    private final PasswordPolicy passwordPolicy;
    private final TaskAbandonService taskAbandonService;

    public AccountService(UserMapper userMapper, SessionService sessionService, PasswordPolicy passwordPolicy,
                          TaskAbandonService taskAbandonService) {
        this.userMapper = userMapper;
        this.sessionService = sessionService;
        this.passwordPolicy = passwordPolicy;
        this.taskAbandonService = taskAbandonService;
    }

    /**
     * 校验当前密码（OQ-25 余项临时默认：需要）→ 更新哈希 → 作废其他会话，当前会话保留。
     * 当前密码错误返回 400 {@code CURRENT_PASSWORD_INCORRECT}（不是 401，避免触发前端全局"会话失效"处理）。
     */
    @Transactional
    public void changePassword(long userId, String currentToken, String currentPassword, String newPassword) {
        User user = activeUser(userId);
        if (!passwordPolicy.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.CURRENT_PASSWORD_INCORRECT);
        }
        String hash = passwordPolicy.hashNewPassword("newPassword", newPassword);
        if (userMapper.updatePassword(userId, hash) == 0) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        sessionService.revokeOthers(userId, currentToken);
    }

    /**
     * 同一事务中（architecture §5.5）：软删除用户 → 废弃进行中的任务 → 作废全部会话。
     * 不删除任何任务、素材或文件（OQ-26：数据保留）。
     */
    @Transactional
    public void deleteAccount(long userId, String confirmText) {
        if (!DELETE_CONFIRM_TEXT.equals(confirmText)) {
            throw new ApiException(ErrorCode.CONFIRM_TEXT_MISMATCH);
        }
        if (userMapper.softDelete(userId) == 0) {
            // 并发删除：另一请求已删除该账户，当前会话随之失效
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        taskAbandonService.abandonActiveByUser(userId);
        sessionService.revokeAll(userId);
    }

    private User activeUser(long userId) {
        User user = userMapper.findActiveById(userId);
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        return user;
    }
}
