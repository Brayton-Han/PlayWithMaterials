package com.xiaoying.task.pipeline;

import static com.xiaoying.task.pipeline.StepFixtures.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xiaoying.simulation.SimulationConfig;
import com.xiaoying.task.domain.FailureCode;
import com.xiaoying.task.domain.TaskStage;

/** T-043：每步开头的模拟控制——按快照耗时等待、指定阶段失败（默认 / 自定义文案）、中断传播；GenerateStep 只经过 gate。 */
class SimulationGateTest {

    private static final SimulationConfig DELAYS = new SimulationConfig(11, 22, 33, null, null);

    private final List<Long> sleeps = new ArrayList<>();
    private final SimulationGate gate = new SimulationGate(sleeps::add);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @ParameterizedTest
    @EnumSource(TaskStage.class)
    void sleepsForTheStageDurationFromTheSnapshotAndPassesWhenNoFailStage(TaskStage stage) {
        assertThatCode(() -> gate.check(stage, context(DELAYS, List.of()))).doesNotThrowAnyException();

        long expected = switch (stage) {
            case ASSET_CHECK -> 11;
            case GENERATING -> 22;
            case RESULT_ORGANIZING -> 33;
        };
        assertThat(sleeps).containsExactly(expected);
    }

    @Test
    void zeroDurationDoesNotSleep() throws InterruptedException {
        gate.check(TaskStage.GENERATING, context(StepFixtures.NO_SIMULATION, List.of()));
        assertThat(sleeps).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(TaskStage.class)
    void matchingFailStageThrowsSimulatedFailureWithDefaultReasonAfterSleeping(TaskStage stage) {
        SimulationConfig sim = new SimulationConfig(5, 5, 5, stage, null);

        assertThatThrownBy(() -> gate.check(stage, context(sim, List.of())))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo(FailureCode.SIMULATED_FAILURE);
                    assertThat(e.reason()).isEqualTo(SimulationGate.defaultReason(stage)).startsWith("模拟失败：");
                });
        assertThat(sleeps).containsExactly(5L);   // 先等待，再失败
    }

    @Test
    void defaultReasonsAreTheDocumentedChineseTexts() {
        assertThat(SimulationGate.defaultReason(TaskStage.GENERATING)).isEqualTo("模拟失败：生成阶段出错");
        assertThat(SimulationGate.defaultReason(TaskStage.ASSET_CHECK)).isEqualTo("模拟失败：素材检查阶段出错");
        assertThat(SimulationGate.defaultReason(TaskStage.RESULT_ORGANIZING)).isEqualTo("模拟失败：结果整理阶段出错");
    }

    @Test
    void customReasonFromTheSnapshotIsUsedVerbatim() {
        SimulationConfig sim = StepFixtures.failAt(TaskStage.GENERATING, "create watermark failed");

        assertThatThrownBy(() -> gate.check(TaskStage.GENERATING, context(sim, List.of())))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo(FailureCode.SIMULATED_FAILURE);
                    assertThat(e.reason()).isEqualTo("create watermark failed");
                });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void blankCustomReasonFallsBackToDefault(String reason) {
        SimulationConfig sim = StepFixtures.failAt(TaskStage.ASSET_CHECK, reason);

        assertThatThrownBy(() -> gate.check(TaskStage.ASSET_CHECK, context(sim, List.of())))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.reason()).isEqualTo("模拟失败：素材检查阶段出错"));
    }

    @ParameterizedTest
    @EnumSource(value = TaskStage.class, names = {"ASSET_CHECK", "RESULT_ORGANIZING"})
    void otherFailStageDoesNotAffectThisStage(TaskStage stage) {
        SimulationConfig sim = StepFixtures.failAt(TaskStage.GENERATING, null);
        assertThatCode(() -> gate.check(stage, context(sim, List.of()))).doesNotThrowAnyException();
    }

    @Test
    void interruptionDuringTheWaitPropagatesAndDoesNotFail() {
        SimulationGate interrupted = new SimulationGate(ms -> {
            throw new InterruptedException("shutdown");
        });
        SimulationConfig sim = new SimulationConfig(0, 1000, 0, TaskStage.GENERATING, null);

        // 等待被中断时直接抛 InterruptedException（任务保持原状 [OQ-13]），不会再走到模拟失败
        assertThatThrownBy(() -> interrupted.check(TaskStage.GENERATING, context(sim, List.of())))
                .isInstanceOf(InterruptedException.class);
    }

    @Test
    void realSleepIsInterruptible() {
        SimulationGate real = new SimulationGate();
        SimulationConfig sim = new SimulationConfig(0, 60_000, 0, null, null);
        Thread.currentThread().interrupt();

        long start = System.nanoTime();
        assertThatThrownBy(() -> real.check(TaskStage.GENERATING, context(sim, List.of())))
                .isInstanceOf(InterruptedException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1000);
    }

    @Test
    void generateStepOnlyWaitsThroughTheGate() throws Exception {
        GenerateStep step = new GenerateStep(gate);
        TaskContext ctx = context(DELAYS, List.of());

        step.execute(ctx);

        assertThat(step.stage()).isEqualTo(TaskStage.GENERATING);
        assertThat(sleeps).containsExactly(22L);
        assertThat(ctx.resultUrl()).isNull();

        assertThatThrownBy(() -> step.execute(context(StepFixtures.failAt(TaskStage.GENERATING, null), List.of())))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo(FailureCode.SIMULATED_FAILURE));
    }
}
