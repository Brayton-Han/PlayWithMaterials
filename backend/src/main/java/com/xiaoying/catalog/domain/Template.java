package com.xiaoying.catalog.domain;

import java.util.List;

/**
 * {@code templates} 表行。{@code categoryName} 来自 JOIN categories（仅详情查询填充）。
 * {@code sampleResultPath} 供结果整理步骤（T-043）使用，永远不进入 DTO（api.yaml TemplateDetail）。
 */
public class Template {

    private String id;
    private String categoryId;
    private String categoryName;
    private String title;
    private String description;
    private String coverUrl;
    private String sampleResultPath;
    private int requiredImageCount;
    private List<String> acceptedMimeTypes;
    private long maxFileSizeBytes;
    private int sortOrder;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public String getSampleResultPath() { return sampleResultPath; }
    public void setSampleResultPath(String sampleResultPath) { this.sampleResultPath = sampleResultPath; }
    public int getRequiredImageCount() { return requiredImageCount; }
    public void setRequiredImageCount(int requiredImageCount) { this.requiredImageCount = requiredImageCount; }
    public List<String> getAcceptedMimeTypes() { return acceptedMimeTypes; }
    public void setAcceptedMimeTypes(List<String> acceptedMimeTypes) { this.acceptedMimeTypes = acceptedMimeTypes; }
    public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
    public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
