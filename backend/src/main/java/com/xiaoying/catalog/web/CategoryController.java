package com.xiaoying.catalog.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xiaoying.catalog.service.CatalogService;
import com.xiaoying.common.web.ItemsDto;

/** {@code GET /api/categories}（api.yaml tag catalog，匿名可访问）。 */
@RestController
public class CategoryController {

    private final CatalogService catalogService;

    public CategoryController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/api/categories")
    public ItemsDto<CategoryDto> list() {
        return ItemsDto.of(catalogService.listCategories().stream().map(CategoryDto::from).toList());
    }
}
