package com.xiaoying.catalog.web;

import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.xiaoying.catalog.service.CatalogService;
import com.xiaoying.common.web.ItemsDto;

/** {@code GET /api/templates}、{@code GET /api/templates/{templateId}}（api.yaml tag catalog，匿名可访问）。 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final CatalogService catalogService;

    public TemplateController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public ItemsDto<TemplateSummaryDto> list(
            @RequestParam(required = false) @Size(max = 32, message = "分类 ID 不能超过 32 个字符") String categoryId) {
        return ItemsDto.of(catalogService.listTemplates(categoryId).stream().map(TemplateSummaryDto::from).toList());
    }

    /** 契约中该接口只有 404：超长 ID 同样不可能存在，按 TEMPLATE_NOT_FOUND 处理。 */
    @GetMapping("/{templateId}")
    public TemplateDetailDto get(@PathVariable String templateId) {
        return TemplateDetailDto.from(catalogService.getTemplate(templateId));
    }
}
