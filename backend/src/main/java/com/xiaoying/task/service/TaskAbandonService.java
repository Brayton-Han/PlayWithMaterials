package com.xiaoying.task.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.xiaoying.task.mapper.TaskMapper;

/**
 * 删除账户时废弃该用户进行中的任务（F-28 规则 5，architecture §5.5）。
 * <p>
 * 用条件更新把 PENDING / PROCESSING 改为 {@code FAILED + ACCOUNT_DELETED} [OQ-26 余项临时默认]；
 * 执行器下一次 {@code markProcessing} / {@code markSucceeded} 影响 0 行即停止，不会覆盖这一终态。
 */
@Service
public class TaskAbandonService {

    private final TaskMapper taskMapper;

    public TaskAbandonService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /** 必须在调用方（删除账户）的事务中执行，与软删除、作废会话一起提交或回滚。返回废弃的任务数。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int abandonActiveByUser(long userId) {
        return taskMapper.abandonActiveByUser(userId);
    }
}
