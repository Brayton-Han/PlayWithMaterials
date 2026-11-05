package com.xiaoying.catalog.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import com.xiaoying.catalog.domain.Category;

@Mapper
public interface CategoryMapper {

    /** 全部分类，按 sort_order、id 升序。 */
    List<Category> findAll();
}
