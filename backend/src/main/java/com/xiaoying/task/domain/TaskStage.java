package com.xiaoying.task.domain;

/** {@code tasks.stage} / {@code failed_stage}，严格按声明顺序执行（api.yaml TaskStage）。 */
public enum TaskStage {
    ASSET_CHECK,
    GENERATING,
    RESULT_ORGANIZING
}
