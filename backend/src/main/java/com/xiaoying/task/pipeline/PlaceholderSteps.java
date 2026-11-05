package com.xiaoying.task.pipeline;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.xiaoying.catalog.domain.Template;
import com.xiaoying.catalog.mapper.TemplateMapper;
import com.xiaoying.simulation.SimulationConfig;
import com.xiaoying.task.domain.TaskStage;

/**
 * <b>T-042 临时占位步骤，T-043 实现 {@code AssetCheckStep} / {@code GenerateStep} / {@code ResultOrganizeStep} 时整体删除本类。</b>
 *
 * <p>只为让执行引擎能在真实环境中端到端运行（T-042 完成定义：curl 提交后能在 DB 中观察到 stage 变化）：
 * 每步按任务快照中的耗时等待（可中断）；结果整理返回模板预置结果图的公开地址 {@code /files/seed/results/...}
 * （满足 {@code chk_tasks_succeeded}）。不做素材检查、不复制结果文件、不处理 {@code failStage}——这些属于 T-043。
 */
@Configuration
public class PlaceholderSteps {

    @Bean
    TaskStep placeholderAssetCheckStep() {
        return new DelayStep(TaskStage.ASSET_CHECK, null);
    }

    @Bean
    TaskStep placeholderGenerateStep() {
        return new DelayStep(TaskStage.GENERATING, null);
    }

    @Bean
    TaskStep placeholderResultOrganizeStep(TemplateMapper templates) {
        return new DelayStep(TaskStage.RESULT_ORGANIZING, templates);
    }

    private record DelayStep(TaskStage stage, TemplateMapper templates) implements TaskStep {

        @Override
        public void execute(TaskContext context) throws InterruptedException {
            Thread.sleep(delayMs(context.simulation()));
            if (templates != null) {
                Template template = templates.findById(context.templateId());
                if (template == null) {
                    throw new IllegalStateException("template not found: " + context.templateId());
                }
                context.setResultUrl("/files/" + template.getSampleResultPath());
            }
        }

        private long delayMs(SimulationConfig sim) {
            long ms = switch (stage) {
                case ASSET_CHECK -> sim.assetCheckMs();
                case GENERATING -> sim.generatingMs();
                case RESULT_ORGANIZING -> sim.resultOrganizingMs();
            };
            return Math.max(0, ms);
        }
    }
}
