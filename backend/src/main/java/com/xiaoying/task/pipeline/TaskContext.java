package com.xiaoying.task.pipeline;

import java.util.List;

import com.xiaoying.simulation.SimulationConfig;
import com.xiaoying.task.domain.Task;
import com.xiaoying.task.domain.TaskAsset;

/**
 * 一次 pipeline 运行中各步骤共享的上下文（architecture §5.2）：任务开始执行时读取的任务行、素材（按 slotIndex 升序）、
 * 模拟配置快照 [OQ-10]；结果整理步骤通过 {@link #setResultUrl} 返回结果地址，pipeline 在全部步骤成功后写入。
 * 只在一个工作线程内使用，不需要同步。
 */
public final class TaskContext {

    private final Task task;
    private final List<TaskAsset> assets;
    private final SimulationConfig simulation;
    private String resultUrl;

    public TaskContext(Task task, List<TaskAsset> assets, SimulationConfig simulation) {
        this.task = task;
        this.assets = List.copyOf(assets);
        this.simulation = simulation;
    }

    public long taskId() {
        return task.getId();
    }

    public long userId() {
        return task.getUserId();
    }

    public String templateId() {
        return task.getTemplateId();
    }

    /** 开始执行时读取的任务行（快照，之后的状态变化不会反映在这里）。 */
    public Task task() {
        return task;
    }

    public List<TaskAsset> assets() {
        return assets;
    }

    public SimulationConfig simulation() {
        return simulation;
    }

    public String resultUrl() {
        return resultUrl;
    }

    public void setResultUrl(String resultUrl) {
        this.resultUrl = resultUrl;
    }
}
