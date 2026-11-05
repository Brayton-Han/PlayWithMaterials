package com.xiaoying.task.web;

import java.util.List;

import com.xiaoying.task.domain.FailureCode;
import com.xiaoying.task.domain.TaskStage;
import com.xiaoying.task.domain.TaskStatus;

/**
 * api.yaml Task（F-07 任务视图）。所有字段都必返，可空字段输出为 {@code null}。
 * 不含 {@code userId}、{@code simConfig}（architecture §3.1 规则 1）。
 */
public record TaskDto(String taskId, String templateId, String templateTitle, String templateCoverUrl,
                      TaskStatus status, TaskStage stage, TaskStage failedStage, FailureCode failureCode,
                      String failureReason, String resultUrl, List<InputAssetDto> inputAssets,
                      String createdAt, String updatedAt, String startedAt, String finishedAt) {
}
