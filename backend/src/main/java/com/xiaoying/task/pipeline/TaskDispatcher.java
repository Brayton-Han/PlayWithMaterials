package com.xiaoying.task.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * 把已受理（事务已提交）的任务交给后台执行（architecture §5.1 / §5.2）。
 *
 * <p>T-041 只定义派发入口：调用方（{@code TaskSubmissionService}）在数据库事务提交、文件 commit 之后调用，
 * 并处理 {@link TaskRejectedException}（队列已满 → 撤销任务与文件，503 QUEUE_FULL [OQ-12]）。
 * 有界线程池与 {@code TaskPipeline} 由 T-042 实现；在此之前任务停留在 PENDING。
 */
@Component
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    /**
     * 派发任务，立即返回，不等待执行。
     *
     * @throws TaskRejectedException 执行器拒绝（队列已满），任务不会被执行
     */
    public void dispatch(long taskId) {
        log.info("task {} accepted; execution pipeline not wired yet (T-042)", taskId);
    }
}
