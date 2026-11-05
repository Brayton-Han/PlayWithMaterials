package com.xiaoying.catalog.web;

import com.xiaoying.catalog.domain.Template;

/** api.yaml TemplateSummary（列表卡片字段，F-01）。 */
public record TemplateSummaryDto(String templateId, String title, String categoryId, String coverUrl) {

    public static TemplateSummaryDto from(Template template) {
        return new TemplateSummaryDto(template.getId(), template.getTitle(), template.getCategoryId(),
                template.getCoverUrl());
    }
}
