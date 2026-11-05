package com.xiaoying.catalog.web;

import java.util.List;

import com.xiaoying.catalog.domain.Template;

/** api.yaml TemplateDetail（F-02）。不含 sample_result_path。 */
public record TemplateDetailDto(String templateId, String title, String description, String categoryId,
                                String categoryName, String coverUrl, int requiredImageCount,
                                List<String> acceptedMimeTypes, long maxFileSizeBytes) {

    public static TemplateDetailDto from(Template t) {
        return new TemplateDetailDto(t.getId(), t.getTitle(), t.getDescription(), t.getCategoryId(),
                t.getCategoryName(), t.getCoverUrl(), t.getRequiredImageCount(), t.getAcceptedMimeTypes(),
                t.getMaxFileSizeBytes());
    }
}
