package com.xiaoying.task.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * 把已受理（事务已提交）的任务交给后台线程池执行（architecture §5.1 / §5.2）。
 *
 * <p>调用方（{@code TaskSubmissionService}）在数据库事务提交、文件 commit 之后调用，所以工作线程一定能读到任务；
 * 并处理 {@link TaskRejectedException}（队列已满 → 撤销任务与文件，503 QUEUE_FULL [OQ-12]）。
 */
@Component
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final TaskExecutor executor;
    private final TaskPipeline pipeline;

    public TaskDispatcher(@Qualifier(TaskExecutorConfig.EXECUTOR_BEAN) TaskExecutor executor, TaskPipeline pipeline) {
        this.executor = executor;
        this.pipeline = pipeline;
    }

    /**
     * 派发任务，立即返回，不等待执行。
     *
     * @throws TaskRejectedException 执行器拒绝（队列已满或正在停机），任务不会被执行
     */
    public void dispatch(long taskId) {
        executor.execute(() -> pipeline.run(taskId));
        log.info("task {} dispatched", taskId);
    }
}
