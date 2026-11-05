package com.xiaoying.task.pipeline;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.xiaoying.simulation.SimulationConfig;
import com.xiaoying.simulation.SimulationSettings;
import com.xiaoying.task.domain.FailureCode;
import com.xiaoying.task.domain.Task;
import com.xiaoying.task.domain.TaskAsset;
import com.xiaoying.task.domain.TaskStage;
import com.xiaoying.task.mapper.TaskAssetMapper;
import com.xiaoying.task.mapper.TaskMapper;

/**
 * 在工作线程中按顺序执行一个任务的全部步骤（F-06，architecture §5.2）。
 * <ol>
 *   <li>每一步之前 {@code markProcessing(stage)}，持久化执行边界（R-05.1）；</li>
 *   <li>步骤抛 {@link StepFailedException} → {@code markFailed(stage, code, reason)}，不执行后续步骤；</li>
 *   <li>任何其他 {@link Throwable} → 兜底 {@code FAILED / INTERNAL_ERROR}，原因用通用文案，堆栈只写日志（F-06 规则 6、7）；</li>
 *   <li>全部成功 → {@code markSucceeded(resultUrl)}；</li>
 *   <li>任何条件更新影响 0 行（任务已被外部结束，如删除账户时废弃）→ 立即停止，不执行后续步骤、不写结果（F-06 规则 8）；</li>
 *   <li>线程被中断（停机）→ 保持原状、恢复中断标记 [OQ-13]。</li>
 * </ol>
 * {@link #run} 从不向调用方抛异常。每步记录开始 / 结束 / 耗时 / 结果，日志消息本身带 taskId 与 stage；
 * 同时放入 MDC（{@code taskId}、{@code stage}），步骤内部的日志也可按需在日志格式中输出。
 */
@Component
public class TaskPipeline {

    /** INTERNAL_ERROR 的用户可读原因（通用文案，不含技术细节）[OQ-11 临时默认]。 */
    static final String INTERNAL_ERROR_REASON = "系统内部错误，任务处理失败";

    static final String MDC_TASK_ID = "taskId";
    static final String MDC_STAGE = "stage";

    private static final Logger log = LoggerFactory.getLogger(TaskPipeline.class);

    private final TaskMapper taskMapper;
    private final TaskAssetMapper assetMapper;
    private final Map<TaskStage, TaskStep> steps;

    public TaskPipeline(TaskMapper taskMapper, TaskAssetMapper assetMapper, List<TaskStep> steps) {
        this.taskMapper = taskMapper;
        this.assetMapper = assetMapper;
        this.steps = index(steps);
    }

    /** 每个阶段恰好一个步骤；缺失或重复时启动失败，而不是在运行时才发现。 */
    private static Map<TaskStage, TaskStep> index(List<TaskStep> steps) {
        Map<TaskStage, TaskStep> byStage = new EnumMap<>(TaskStage.class);
        for (TaskStep step : steps) {
            TaskStep previous = byStage.putIfAbsent(step.stage(), step);
            if (previous != null) {
                throw new IllegalStateException("duplicate TaskStep for stage %s: %s and %s".formatted(
                        step.stage(), previous.getClass().getName(), step.getClass().getName()));
            }
        }
        for (TaskStage stage : TaskStage.values()) {
            if (!byStage.containsKey(stage)) {
                throw new IllegalStateException("no TaskStep registered for stage " + stage);
            }
        }
        return byStage;
    }

    /** 执行任务直到终态（或被外部结束 / 中断）。不抛异常。 */
    public void run(long taskId) {
        MDC.put(MDC_TASK_ID, Long.toString(taskId));
        long startedNanos = System.nanoTime();
        TaskStage current = null;
        try {
            Task task = taskMapper.findById(taskId);
            if (task == null) {
                log.warn("task {} not found, skipped", taskId);
                return;
            }
            if (task.getStatus().isTerminal()) {
                log.info("task {} already {}, skipped", taskId, task.getStatus());
                return;
            }
            List<TaskAsset> assets = assetMapper.listByTaskIdsAndUser(task.getUserId(), List.of(taskId));
            SimulationConfig simulation = SimulationSettings.parseSnapshot(task.getSimConfig());
            TaskContext context = new TaskContext(task, assets, simulation);
            log.info("task {} started: template={}, assets={}", taskId, task.getTemplateId(), assets.size());

            for (TaskStage stage : TaskStage.values()) {
                current = stage;
                MDC.put(MDC_STAGE, stage.name());
                if (!runStep(stage, context)) {
                    return;
                }
            }
            MDC.remove(MDC_STAGE);

            String resultUrl = context.resultUrl();
            if (resultUrl == null || resultUrl.isBlank()) {
                log.error("task {}: all steps finished but no resultUrl was produced", taskId);
                fail(taskId, TaskStage.RESULT_ORGANIZING, FailureCode.INTERNAL_ERROR, INTERNAL_ERROR_REASON);
                return;
            }
            if (taskMapper.markSucceeded(taskId, resultUrl) == 0) {
                log.info("task {} finished externally before success could be recorded; result discarded", taskId);
                return;
            }
            log.info("task {} SUCCEEDED in {} ms: resultUrl={}", taskId, elapsedMs(startedNanos), resultUrl);
        } catch (Throwable t) {
            // 读取任务 / 解析快照 / 状态写入本身出错：兜底为 FAILED（尽力而为，数据库不可用时只能记日志）
            log.error("task {} pipeline crashed at stage {}", taskId, current, t);
            failQuietly(taskId, current);
        } finally {
            MDC.remove(MDC_STAGE);
            MDC.remove(MDC_TASK_ID);
        }
    }

    /** @return true 继续下一步；false 已结束（失败 / 被外部结束 / 被中断） */
    private boolean runStep(TaskStage stage, TaskContext context) {
        long taskId = context.taskId();
        if (taskMapper.markProcessing(taskId, stage) == 0) {
            log.info("task {} finished externally, stopping before {}", taskId, stage);
            return false;
        }
        log.info("task {} step {} started", taskId, stage);
        long stepNanos = System.nanoTime();
        try {
            steps.get(stage).execute(context);
        } catch (StepFailedException e) {
            log.warn("task {} step {} FAILED after {} ms: code={}, reason={}", taskId, stage, elapsedMs(stepNanos),
                    e.code(), e.reason(), e.getCause());
            fail(taskId, stage, e.code(), e.reason());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("task {} step {} interrupted after {} ms (shutdown?); task left as-is [OQ-13]", taskId, stage,
                    elapsedMs(stepNanos));
            return false;
        } catch (Throwable t) {
            log.error("task {} step {} crashed after {} ms", taskId, stage, elapsedMs(stepNanos), t);
            fail(taskId, stage, FailureCode.INTERNAL_ERROR, INTERNAL_ERROR_REASON);
            return false;
        }
        log.info("task {} step {} finished in {} ms: OK", taskId, stage, elapsedMs(stepNanos));
        return true;
    }

    private void fail(long taskId, TaskStage stage, FailureCode code, String reason) {
        if (taskMapper.markFailed(taskId, stage, code, reason) == 0) {
            log.info("task {} finished externally, failure ({}) not recorded", taskId, code);
        } else {
            log.info("task {} FAILED at {}: code={}", taskId, stage, code);
        }
    }

    private void failQuietly(long taskId, TaskStage stage) {
        try {
            fail(taskId, stage, FailureCode.INTERNAL_ERROR, INTERNAL_ERROR_REASON);
        } catch (Throwable t) {
            log.error("could not mark task {} FAILED; it may stay unfinished", taskId, t);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
