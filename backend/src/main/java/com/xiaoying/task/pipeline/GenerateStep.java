package com.xiaoying.task.pipeline;

import org.springframework.stereotype.Component;

import com.xiaoying.task.domain.TaskStage;

/**
 * 模拟生成（F-06 规则 1.2）：只按快照耗时等待（可中断），不做真实图片处理（R-05.1）。
 * 等待与指定阶段失败都由 {@link SimulationGate} 完成。
 */
@Component
public class GenerateStep implements TaskStep {

    private final SimulationGate gate;

    public GenerateStep(SimulationGate gate) {
        this.gate = gate;
    }

    @Override
    public TaskStage stage() {
        return TaskStage.GENERATING;
    }

    @Override
    public void execute(TaskContext context) throws InterruptedException {
        gate.check(stage(), context);
    }
}
