package com.xiaoying.catalog.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.xiaoying.catalog.domain.Category;
import com.xiaoying.catalog.domain.Template;
import com.xiaoying.catalog.mapper.CategoryMapper;
import com.xiaoying.catalog.mapper.TemplateMapper;
import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;

/** 分类与模板（只读，F-01、F-02）。数据全部来自 V2 种子，不提供写操作（R-02.3）。 */
@Service
public class CatalogService {

    private final CategoryMapper categoryMapper;
    private final TemplateMapper templateMapper;

    public CatalogService(CategoryMapper categoryMapper, TemplateMapper templateMapper) {
        this.categoryMapper = categoryMapper;
        this.templateMapper = templateMapper;
    }

    public List<Category> listCategories() {
        return categoryMapper.findAll();
    }

    /** {@code categoryId} 为 null 或空白时返回全部；不存在的分类返回空列表（api.yaml listTemplates）。 */
    public List<Template> listTemplates(String categoryId) {
        String filter = (categoryId == null || categoryId.isBlank()) ? null : categoryId;
        return templateMapper.findSummaries(filter);
    }

    /** 不存在时抛 404 {@code TEMPLATE_NOT_FOUND}。任务提交（T-041）也通过这里读取模板。 */
    public Template getTemplate(String templateId) {
        Template template = templateMapper.findById(templateId);
        if (template == null) {
            throw new ApiException(ErrorCode.TEMPLATE_NOT_FOUND);
        }
        return template;
    }
}
