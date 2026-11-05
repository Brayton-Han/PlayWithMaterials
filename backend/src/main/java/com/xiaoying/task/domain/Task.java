package com.xiaoying.task.domain;

import java.time.Instant;

/**
 * {@code tasks} 表行。{@code templateTitle}、{@code templateCoverUrl} 来自 JOIN templates（查询时填充）。
 * {@code userId}、{@code simConfig}（JSON 字符串快照，OQ-10）永远不进入 DTO（architecture §3.1）。
 * 状态只能通过 {@code TaskMapper} 的条件更新修改（database.md §2.5）。
 */
public class Task {

    private Long id;
    private Long userId;
    private String templateId;
    private String templateTitle;
    private String templateCoverUrl;
    private TaskStatus status;
    private TaskStage stage;
    private TaskStage failedStage;
    private FailureCode failureCode;
    private String failureReason;
    private String resultUrl;
    private String simConfig;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant updatedAt;

    public Task() {
    }

    /** 新建任务（插入前）：status / stage 由 insert 固定为 PENDING / NULL。 */
    public Task(long userId, String templateId, String simConfig) {
        this.userId = userId;
        this.templateId = templateId;
        this.simConfig = simConfig;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public String getTemplateTitle() { return templateTitle; }
    public void setTemplateTitle(String templateTitle) { this.templateTitle = templateTitle; }
    public String getTemplateCoverUrl() { return templateCoverUrl; }
    public void setTemplateCoverUrl(String templateCoverUrl) { this.templateCoverUrl = templateCoverUrl; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public TaskStage getStage() { return stage; }
    public void setStage(TaskStage stage) { this.stage = stage; }
    public TaskStage getFailedStage() { return failedStage; }
    public void setFailedStage(TaskStage failedStage) { this.failedStage = failedStage; }
    public FailureCode getFailureCode() { return failureCode; }
    public void setFailureCode(FailureCode failureCode) { this.failureCode = failureCode; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getResultUrl() { return resultUrl; }
    public void setResultUrl(String resultUrl) { this.resultUrl = resultUrl; }
    public String getSimConfig() { return simConfig; }
    public void setSimConfig(String simConfig) { this.simConfig = simConfig; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
