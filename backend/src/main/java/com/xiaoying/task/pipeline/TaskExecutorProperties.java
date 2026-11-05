package com.xiaoying.task.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * {@code app.task.executor.*}（architecture §5.2 规则 5、§7）[OQ-12 临时默认 2 线程 / 队列 100]。
 * 环境变量覆盖：{@code APP_TASK_EXECUTOR_POOL_SIZE}、{@code APP_TASK_EXECUTOR_QUEUE_CAPACITY}。
 *
 * @param poolSize      工作线程数（核心 = 最大）
 * @param queueCapacity 等待队列容量；满了之后新任务被拒绝（提交接口返回 503 QUEUE_FULL，不建任务）
 */
@Validated
@ConfigurationProperties("app.task.executor")
public record TaskExecutorProperties(@Min(1) @Max(64) Integer poolSize, @Min(0) @Max(10_000) Integer queueCapacity) {

    public TaskExecutorProperties {
        if (poolSize == null) {
            poolSize = 2;
        }
        if (queueCapacity == null) {
            queueCapacity = 100;
        }
    }
}
