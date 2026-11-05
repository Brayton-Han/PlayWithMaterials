package com.xiaoying.common.web;

import java.util.List;

/** 列表统一包装 {@code { "items": [...] }}（api.yaml 通用约定）。 */
public record ItemsDto<T>(List<T> items) {

    public static <T> ItemsDto<T> of(List<T> items) {
        return new ItemsDto<>(items);
    }
}
