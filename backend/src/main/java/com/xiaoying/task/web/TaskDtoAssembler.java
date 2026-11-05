package com.xiaoying.task.web;

import java.util.Comparator;
import java.util.List;

import com.xiaoying.common.web.ApiTime;
import com.xiaoying.task.domain.Task;
import com.xiaoying.task.domain.TaskAsset;
import com.xiaoying.task.domain.TaskView;

/** 任务实体 → {@link TaskDto}（查询与提交接口共用）。 */
public final class TaskDtoAssembler {

    /** 用户文件 URL 前缀：URL = {@code /files/} + storage_key（database.md §2.6）。 */
    static final String FILES_PREFIX = "/files/";

    private TaskDtoAssembler() {
    }

    public static TaskDto toDto(TaskView view) {
        Task t = view.task();
        List<InputAssetDto> inputAssets = view.assets().stream()
                .sorted(Comparator.comparingInt(TaskAsset::getSlotIndex))
                .map(a -> new InputAssetDto(a.getSlotIndex(), FILES_PREFIX + a.getStorageKey()))
                .toList();
        return new TaskDto(String.valueOf(t.getId()), t.getTemplateId(), t.getTemplateTitle(),
                t.getTemplateCoverUrl(), t.getStatus(), t.getStage(), t.getFailedStage(), t.getFailureCode(),
                t.getFailureReason(), t.getResultUrl(), inputAssets,
                ApiTime.format(t.getCreatedAt()), ApiTime.format(t.getUpdatedAt()),
                ApiTime.format(t.getStartedAt()), ApiTime.format(t.getFinishedAt()));
    }
}
