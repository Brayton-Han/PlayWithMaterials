package com.xiaoying.task.domain;

import java.time.Instant;

/**
 * {@code task_assets} 表行（database.md §2.6）。归属通过 task → user 判断；
 * URL 由 {@code storageKey} 在 DTO 转换时生成（{@code /files/} + storageKey），{@code storageKey} 本身不进入 DTO。
 */
public class TaskAsset {

    private Long id;
    private Long taskId;
    private int slotIndex;
    private String storageKey;
    private String originalName;
    private String mimeType;
    private long sizeBytes;
    private Instant createdAt;

    public TaskAsset() {
    }

    public TaskAsset(long taskId, int slotIndex, String storageKey, String originalName, String mimeType,
                     long sizeBytes) {
        this.taskId = taskId;
        this.slotIndex = slotIndex;
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public int getSlotIndex() { return slotIndex; }
    public void setSlotIndex(int slotIndex) { this.slotIndex = slotIndex; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
