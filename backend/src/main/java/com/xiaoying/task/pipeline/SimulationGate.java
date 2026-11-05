package com.xiaoying.task.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.xiaoying.simulation.SimulationConfig;
import com.xiaoying.task.domain.FailureCode;
import com.xiaoying.task.domain.TaskStage;

/**
 * 每个步骤开头调用的模拟控制（F-08，architecture §5.2）：
 * <ol>
 *   <li>按任务快照中该阶段的耗时等待（可被中断，停机时抛 {@link InterruptedException}）；</li>
 *   <li>快照 {@code failStage} 等于当前阶段 → 抛 {@link StepFailedException}({@code SIMULATED_FAILURE})，
 *       原因为快照中的 {@code failReason}，为空时使用按阶段的默认文案 [OQ-11 临时默认]。</li>
 * </ol>
 * 只读快照（{@link TaskContext#simulation()}），不看当前全局配置 [OQ-10]。
 */
@Component
public class SimulationGate {

    /** 可替换的等待方式（单测中不真正 sleep）。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Sleeper sleeper;

    @Autowired
    public SimulationGate() {
        this(Thread::sleep);
    }

    SimulationGate(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /** 本阶段的模拟等待 + 指定阶段失败。 */
    public void check(TaskStage stage, TaskContext context) throws InterruptedException {
        SimulationConfig sim = context.simulation();
        long ms = delayMs(stage, sim);
        if (ms > 0) {
            sleeper.sleep(ms);
        }
        if (sim.failStage() == stage) {
            String reason = sim.failReason();
            throw new StepFailedException(FailureCode.SIMULATED_FAILURE,
                    reason == null || reason.isBlank() ? defaultReason(stage) : reason);
        }
    }

    /** 模拟失败的默认文案 [OQ-11 临时默认]，如"模拟失败：生成阶段出错"（api.yaml SimulationConfig.failReason）。 */
    public static String defaultReason(TaskStage stage) {
        return switch (stage) {
            case ASSET_CHECK -> "模拟失败：素材检查阶段出错";
            case GENERATING -> "模拟失败：生成阶段出错";
            case RESULT_ORGANIZING -> "模拟失败：结果整理阶段出错";
        };
    }

    private static long delayMs(TaskStage stage, SimulationConfig sim) {
        return switch (stage) {
            case ASSET_CHECK -> sim.assetCheckMs();
            case GENERATING -> sim.generatingMs();
            case RESULT_ORGANIZING -> sim.resultOrganizingMs();
        };
    }
}
