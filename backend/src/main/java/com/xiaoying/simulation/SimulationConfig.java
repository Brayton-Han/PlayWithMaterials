package com.xiaoying.simulation;

import com.xiaoying.task.domain.TaskStage;

/**
 * 模拟配置值对象（api.yaml SimulationConfig，architecture §5.3）。创建任务时快照到 {@code tasks.sim_config}
 * [OQ-10 临时默认]，执行器只读快照，之后修改全局配置不影响已创建的任务。
 *
 * @param failStage  指定失败的阶段；null 表示不失败
 * @param failReason 自定义失败原因；null 时使用按阶段的默认文案（T-043）
 */
public record SimulationConfig(long assetCheckMs, long generatingMs, long resultOrganizingMs,
                               TaskStage failStage, String failReason) {
}
