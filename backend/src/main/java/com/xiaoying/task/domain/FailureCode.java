package com.xiaoying.task.domain;

/** {@code tasks.failure_code}，对外返回（api.yaml FailureCode，DQ-03 已确认）。 */
public enum FailureCode {
    /** 模拟配置指定的阶段失败 */
    SIMULATED_FAILURE,
    /** 素材检查：文件或记录缺失 */
    ASSET_MISSING,
    /** 素材检查：类型 / 大小复核不通过 */
    ASSET_INVALID,
    /** 结果整理：写结果文件失败 */
    RESULT_WRITE_FAILED,
    /** 账户删除时被废弃 [OQ-26 余项] */
    ACCOUNT_DELETED,
    /** 未预期异常兜底 */
    INTERNAL_ERROR
}
