package com.xiaoying.catalog.web;

import com.xiaoying.catalog.domain.Category;

/** api.yaml Category。 */
public record CategoryDto(String categoryId, String name, int sortOrder) {

    public static CategoryDto from(Category category) {
        return new CategoryDto(category.getId(), category.getName(), category.getSortOrder());
    }
}
