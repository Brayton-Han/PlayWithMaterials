package com.xiaoying.catalog.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.xiaoying.catalog.domain.Template;

@Mapper
public interface TemplateMapper {

    /**
     * 卡片列表。{@code categoryId} 为 null 时返回全部（按分类顺序、再按模板 sort_order，即种子顺序）；
     * 否则只返回该分类，分类不存在时为空列表。
     */
    List<Template> findSummaries(@Param("categoryId") String categoryId);

    /** 详情（含分类名、素材要求、sample_result_path）；不存在返回 null。 */
    Template findById(@Param("id") String id);
}
