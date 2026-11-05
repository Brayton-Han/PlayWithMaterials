package com.xiaoying.task.domain;

import java.util.List;

/** 任务读模型：任务行 + 按 {@code slotIndex} 升序的素材（F-07 任务视图的数据来源）。 */
public record TaskView(Task task, List<TaskAsset> assets) {

    public TaskView {
        assets = List.copyOf(assets);
    }
}
