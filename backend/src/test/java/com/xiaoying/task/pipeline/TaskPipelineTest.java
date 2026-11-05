package com.xiaoying.task.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;

import com.xiaoying.task.domain.FailureCode;
import com.xiaoying.task.domain.Task;
import com.xiaoying.task.domain.TaskAsset;
import com.xiaoying.task.domain.TaskStage;
import com.xiaoying.task.domain.TaskStatus;
import com.xiaoying.task.mapper.TaskAssetMapper;
import com.xiaoying.task.mapper.TaskMapper;

/**
 * T-042 完成定义（backlog）/ T-046 第 1 ~ 3 项（implementation-plan）：用假 step 验证顺序与中断。
 * 成功路径顺序、每步之前 markProcessing、各阶段失败后不执行后续步骤、未预期异常兜底为 INTERNAL_ERROR、
 * 条件更新返回 0（被外部结束）时立即停止、中断时保持原状，以及 run 从不抛异常。
 */
class TaskPipelineTest {

    private static final long TASK = 42L;
    private static final long USER = 7L;
    private static final String SIM = """
            {"assetCheckMs":10,"generatingMs":20,"resultOrganizingMs":30,"failStage":null,"failReason":null}""";
    private static final String RESULT = "/files/results/42/r.png";

    TaskMapper taskMapper = mock(TaskMapper.class);
    TaskAssetMapper assetMapper = mock(TaskAssetMapper.class);
    List<TaskStage> executed = new ArrayList<>();
    Map<TaskStage, FakeStep> steps;
    TaskPipeline pipeline;

    @BeforeEach
    void setUp() {
        Task task = pendingTask(SIM);
        when(taskMapper.findById(TASK)).thenReturn(task);
        when(assetMapper.listByTaskIdsAndUser(USER, List.of(TASK)))
                .thenReturn(List.of(new TaskAsset(TASK, 0, "uploads/2026/11/a.jpg", "a.jpg", "image/jpeg", 10)));
        when(taskMapper.markProcessing(eq(TASK), any())).thenReturn(1);
        when(taskMapper.markSucceeded(eq(TASK), anyString())).thenReturn(1);
        when(taskMapper.markFailed(eq(TASK), any(), any(), anyString())).thenReturn(1);
        steps = Map.of(
                TaskStage.ASSET_CHECK, new FakeStep(TaskStage.ASSET_CHECK),
                TaskStage.GENERATING, new FakeStep(TaskStage.GENERATING),
                TaskStage.RESULT_ORGANIZING, new FakeStep(TaskStage.RESULT_ORGANIZING));
        steps.get(TaskStage.RESULT_ORGANIZING).action = ctx -> ctx.setResultUrl(RESULT);
        // 注册顺序故意打乱：执行顺序只由 TaskStage 声明顺序决定
        pipeline = new TaskPipeline(taskMapper, assetMapper, List.of(
                steps.get(TaskStage.RESULT_ORGANIZING), steps.get(TaskStage.ASSET_CHECK), steps.get(TaskStage.GENERATING)));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // ---------- 成功路径 ----------

    @Test
    void runsStepsInStageOrderMarkingProcessingBeforeEachAndSucceedsWithResultUrl() {
        pipeline.run(TASK);

        assertThat(executed).containsExactly(TaskStage.ASSET_CHECK, TaskStage.GENERATING, TaskStage.RESULT_ORGANIZING);
        InOrder order = inOrder(taskMapper);
        order.verify(taskMapper).markProcessing(TASK, TaskStage.ASSET_CHECK);
        order.verify(taskMapper).markProcessing(TASK, TaskStage.GENERATING);
        order.verify(taskMapper).markProcessing(TASK, TaskStage.RESULT_ORGANIZING);
        order.verify(taskMapper).markSucceeded(TASK, RESULT);
        verify(taskMapper, never()).markFailed(anyLong(), any(), any(), any());
    }

    @Test
    void markProcessingHappensBeforeTheStepRuns() {
        for (FakeStep step : steps.values()) {
            step.before = () -> verify(taskMapper).markProcessing(TASK, step.stage);
        }

        pipeline.run(TASK);

        assertThat(executed).hasSize(3);
        assertThat(steps.values()).allSatisfy(s -> assertThat(s.beforeError).isNull());
    }

    @Test
    void stepsReceiveTaskAssetsAndSimulationSnapshot() {
        AtomicReference<TaskContext> seen = new AtomicReference<>();
        steps.get(TaskStage.ASSET_CHECK).action = seen::set;

        pipeline.run(TASK);

        TaskContext ctx = seen.get();
        assertThat(ctx.taskId()).isEqualTo(TASK);
        assertThat(ctx.userId()).isEqualTo(USER);
        assertThat(ctx.templateId()).isEqualTo("demo-postcard");
        assertThat(ctx.assets()).extracting(TaskAsset::getStorageKey).containsExactly("uploads/2026/11/a.jpg");
        assertThat(ctx.simulation().assetCheckMs()).isEqualTo(10);
        assertThat(ctx.simulation().generatingMs()).isEqualTo(20);
        assertThat(ctx.simulation().resultOrganizingMs()).isEqualTo(30);
        assertThat(ctx.simulation().failStage()).isNull();
    }

    // ---------- 步骤失败 ----------

    @ParameterizedTest
    @EnumSource(TaskStage.class)
    void stepFailureMarksFailedAtThatStageAndSkipsLaterSteps(TaskStage failing) {
        steps.get(failing).action = ctx -> {
            throw new StepFailedException(FailureCode.SIMULATED_FAILURE, "模拟失败：" + failing);
        };

        pipeline.run(TASK);

        List<TaskStage> expected = Arrays.stream(TaskStage.values()).filter(s -> s.ordinal() <= failing.ordinal()).toList();
        assertThat(executed).containsExactlyElementsOf(expected);
        verify(taskMapper).markFailed(TASK, failing, FailureCode.SIMULATED_FAILURE, "模拟失败：" + failing);
        verify(taskMapper, never()).markSucceeded(anyLong(), any());
        for (TaskStage later : TaskStage.values()) {
            if (later.ordinal() > failing.ordinal()) {
                verify(taskMapper, never()).markProcessing(TASK, later);
            }
        }
    }

    @Test
    void stepFailureKeepsItsOwnFailureCode() {
        steps.get(TaskStage.ASSET_CHECK).action = ctx -> {
            throw new StepFailedException(FailureCode.ASSET_MISSING, "素材文件缺失", new java.io.IOException("x"));
        };

        pipeline.run(TASK);

        verify(taskMapper).markFailed(TASK, TaskStage.ASSET_CHECK, FailureCode.ASSET_MISSING, "素材文件缺失");
    }

    @ParameterizedTest
    @EnumSource(TaskStage.class)
    void unexpectedExceptionIsMarkedInternalErrorWithGenericReason(TaskStage failing) {
        steps.get(failing).action = ctx -> {
            throw new IllegalStateException("NPE-like bug with secret /data/storage/path");
        };

        pipeline.run(TASK);

        verify(taskMapper).markFailed(TASK, failing, FailureCode.INTERNAL_ERROR, TaskPipeline.INTERNAL_ERROR_REASON);
        verify(taskMapper, never()).markSucceeded(anyLong(), any());
        assertThat(executed).last().isEqualTo(failing);
    }

    @Test
    void checkedExceptionAndErrorAreAlsoCaught() throws Exception {
        steps.get(TaskStage.GENERATING).checkedAction = () -> {
            throw new java.io.IOException("disk");
        };
        pipeline.run(TASK);
        verify(taskMapper).markFailed(TASK, TaskStage.GENERATING, FailureCode.INTERNAL_ERROR,
                TaskPipeline.INTERNAL_ERROR_REASON);

        executed.clear();
        steps.get(TaskStage.GENERATING).checkedAction = null;
        steps.get(TaskStage.ASSET_CHECK).action = ctx -> {
            throw new StackOverflowError();
        };
        assertThatCode(() -> pipeline.run(TASK)).doesNotThrowAnyException();
        verify(taskMapper).markFailed(TASK, TaskStage.ASSET_CHECK, FailureCode.INTERNAL_ERROR,
                TaskPipeline.INTERNAL_ERROR_REASON);
        assertThat(executed).containsExactly(TaskStage.ASSET_CHECK);
    }

    @Test
    void missingResultUrlIsInternalErrorNotSuccess() {
        steps.get(TaskStage.RESULT_ORGANIZING).action = ctx -> { };

        pipeline.run(TASK);

        verify(taskMapper).markFailed(TASK, TaskStage.RESULT_ORGANIZING, FailureCode.INTERNAL_ERROR,
                TaskPipeline.INTERNAL_ERROR_REASON);
        verify(taskMapper, never()).markSucceeded(anyLong(), any());
    }

    // ---------- 被外部结束（条件更新影响 0 行） ----------

    @ParameterizedTest
    @EnumSource(TaskStage.class)
    void stopsWithoutRunningStepWhenMarkProcessingAffectsNoRows(TaskStage abandonedAt) {
        when(taskMapper.markProcessing(TASK, abandonedAt)).thenReturn(0);

        pipeline.run(TASK);

        assertThat(executed).doesNotContain(abandonedAt)
                .allSatisfy(s -> assertThat(s.ordinal()).isLessThan(abandonedAt.ordinal()));
        for (TaskStage later : TaskStage.values()) {
            if (later.ordinal() > abandonedAt.ordinal()) {
                verify(taskMapper, never()).markProcessing(TASK, later);
            }
        }
        verify(taskMapper, never()).markSucceeded(anyLong(), any());
        verify(taskMapper, never()).markFailed(anyLong(), any(), any(), any());
    }

    @Test
    void abandonedDuringStepThenNextMarkProcessingStopsAndNoResultIsWritten() {
        // 任务在 GENERATING 执行期间被废弃（如删除账户）：下一步的 markProcessing 返回 0
        steps.get(TaskStage.GENERATING).action = ctx -> when(taskMapper.markProcessing(TASK, TaskStage.RESULT_ORGANIZING))
                .thenReturn(0);

        pipeline.run(TASK);

        assertThat(executed).containsExactly(TaskStage.ASSET_CHECK, TaskStage.GENERATING);
        verify(taskMapper, never()).markSucceeded(anyLong(), any());
        verify(taskMapper, never()).markFailed(anyLong(), any(), any(), any());
    }

    @Test
    void markSucceededAffectingNoRowsIsToleratedAndNotOverwritten() {
        when(taskMapper.markSucceeded(TASK, RESULT)).thenReturn(0);

        assertThatCode(() -> pipeline.run(TASK)).doesNotThrowAnyException();

        verify(taskMapper).markSucceeded(TASK, RESULT);
        verify(taskMapper, never()).markFailed(anyLong(), any(), any(), any());
    }

    @Test
    void markFailedAffectingNoRowsIsTolerated() {
        when(taskMapper.markFailed(eq(TASK), any(), any(), anyString())).thenReturn(0);
        steps.get(TaskStage.ASSET_CHECK).action = ctx -> {
            throw new StepFailedException(FailureCode.ASSET_INVALID, "素材类型不符合模板要求");
        };

        assertThatCode(() -> pipeline.run(TASK)).doesNotThrowAnyException();

        verify(taskMapper).markFailed(TASK, TaskStage.ASSET_CHECK, FailureCode.ASSET_INVALID, "素材类型不符合模板要求");
        assertThat(executed).containsExactly(TaskStage.ASSET_CHECK);
    }

    @Test
    void missingOrTerminalTaskIsSkippedWithoutAnyWrite() {
        when(taskMapper.findById(TASK)).thenReturn(null);
        pipeline.run(TASK);

        for (TaskStatus terminal : List.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED)) {
            Task task = pendingTask(SIM);
            task.setStatus(terminal);
            when(taskMapper.findById(TASK)).thenReturn(task);
            pipeline.run(TASK);
        }

        assertThat(executed).isEmpty();
        verify(taskMapper, never()).markProcessing(anyLong(), any());
        verify(taskMapper, never()).markSucceeded(anyLong(), any());
        verify(taskMapper, never()).markFailed(anyLong(), any(), any(), any());
    }

    // ---------- 基础设施异常与中断 ----------

    @Test
    void invalidSimulationSnapshotFailsBeforeAnyStage() {
        when(taskMapper.findById(TASK)).thenReturn(pendingTask("not json"));

        pipeline.run(TASK);

        assertThat(executed).isEmpty();
        verify(taskMapper).markFailed(TASK, null, FailureCode.INTERNAL_ERROR, TaskPipeline.INTERNAL_ERROR_REASON);
        verify(taskMapper, never()).markProcessing(anyLong(), any());
    }

    @Test
    void databaseErrorDuringMarkProcessingIsCaughtAndFallsBackToFailed() {
        when(taskMapper.markProcessing(TASK, TaskStage.GENERATING))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> pipeline.run(TASK)).doesNotThrowAnyException();

        assertThat(executed).containsExactly(TaskStage.ASSET_CHECK);
        verify(taskMapper).markFailed(TASK, TaskStage.GENERATING, FailureCode.INTERNAL_ERROR,
                TaskPipeline.INTERNAL_ERROR_REASON);
    }

    @Test
    void runNeverThrowsEvenWhenDatabaseIsCompletelyDown() {
        when(taskMapper.findById(TASK)).thenThrow(new DataAccessResourceFailureException("db down"));
        when(taskMapper.markFailed(anyLong(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> pipeline.run(TASK)).doesNotThrowAnyException();
        assertThat(executed).isEmpty();
    }

    @Test
    void interruptedStepLeavesTaskAsIsAndRestoresInterruptFlag() {
        steps.get(TaskStage.GENERATING).checkedAction = () -> {
            throw new InterruptedException("shutdown");
        };

        try {
            pipeline.run(TASK);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(executed).containsExactly(TaskStage.ASSET_CHECK, TaskStage.GENERATING);
        verify(taskMapper, never()).markProcessing(TASK, TaskStage.RESULT_ORGANIZING);
        verify(taskMapper, never()).markFailed(anyLong(), any(), any(), any());
        verify(taskMapper, never()).markSucceeded(anyLong(), any());
    }

    @Test
    void mdcCarriesTaskIdAndStageDuringStepAndIsClearedAfterwards() {
        List<String> seen = new ArrayList<>();
        for (FakeStep step : steps.values()) {
            Runnable original = step.before;
            step.before = chain(original, () -> seen.add(MDC.get("taskId") + "/" + MDC.get("stage")));
        }

        pipeline.run(TASK);

        assertThat(seen).containsExactlyInAnyOrder("42/ASSET_CHECK", "42/GENERATING", "42/RESULT_ORGANIZING");
        assertThat(MDC.get("taskId")).isNull();
        assertThat(MDC.get("stage")).isNull();
    }

    // ---------- 注册校验 ----------

    @Test
    void requiresExactlyOneStepPerStage() {
        FakeStep check = new FakeStep(TaskStage.ASSET_CHECK);
        FakeStep generate = new FakeStep(TaskStage.GENERATING);
        FakeStep organize = new FakeStep(TaskStage.RESULT_ORGANIZING);

        assertThatThrownBy(() -> new TaskPipeline(taskMapper, assetMapper, List.of(check, generate)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESULT_ORGANIZING");
        assertThatThrownBy(() -> new TaskPipeline(taskMapper, assetMapper,
                List.of(check, generate, organize, new FakeStep(TaskStage.GENERATING))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
    }

    @Test
    void stepFailedExceptionRequiresCodeAndReadableReason() {
        assertThatThrownBy(() -> new StepFailedException(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StepFailedException(FailureCode.ASSET_MISSING, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- helpers ----------

    private static Task pendingTask(String sim) {
        Task task = new Task(USER, "demo-postcard", sim);
        task.setId(TASK);
        task.setStatus(TaskStatus.PENDING);
        return task;
    }

    private static Runnable chain(Runnable first, Runnable second) {
        return () -> {
            first.run();
            second.run();
        };
    }

    interface CheckedAction {
        void run() throws Exception;
    }

    /** 记录调用顺序的假 step；{@code action} / {@code checkedAction} 控制行为。 */
    final class FakeStep implements TaskStep {
        final TaskStage stage;
        java.util.function.Consumer<TaskContext> action = ctx -> { };
        CheckedAction checkedAction;
        Runnable before = () -> { };
        Throwable beforeError;

        FakeStep(TaskStage stage) {
            this.stage = stage;
        }

        @Override
        public TaskStage stage() {
            return stage;
        }

        @Override
        public void execute(TaskContext context) throws Exception {
            executed.add(stage);
            try {
                before.run();
            } catch (Throwable t) {
                beforeError = t;
            }
            if (checkedAction != null) {
                checkedAction.run();
            }
            action.accept(context);
        }
    }
}
