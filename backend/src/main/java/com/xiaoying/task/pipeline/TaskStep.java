package com.xiaoying.task.pipeline;

import com.xiaoying.task.domain.TaskStage;

/**
 * 任务执行的一步（F-06 规则 1，architecture §5.2）。每个 {@link TaskStage} 恰好对应一个实现（Spring Bean），
 * 由 {@link TaskPipeline} 按 {@link TaskStage} 声明顺序调用；步骤本身不写任务状态。
 */
public interface TaskStep {

    /** 本步对应的阶段；pipeline 在调用 {@link #execute} 之前把任务写为 {@code PROCESSING / stage}。 */
    TaskStage stage();

    /**
     * 执行本步。
     *
     * @throws StepFailedException  可预期的业务失败（带 {@code failureCode} 与用户可读原因），任务落为 FAILED
     * @throws InterruptedException 线程被中断（停机），任务保持原状 [OQ-13]
     * @throws Exception            其他任何异常都由 pipeline 兜底为 {@code INTERNAL_ERROR}
     */
    void execute(TaskContext context) throws Exception;
}
