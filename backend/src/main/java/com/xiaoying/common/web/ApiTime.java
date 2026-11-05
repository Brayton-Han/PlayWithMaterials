package com.xiaoying.common.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 接口时间格式（api.yaml 通用约定）：ISO-8601 UTC，固定毫秒精度，如 {@code 2026-11-04T08:30:00.123Z}。
 * 不依赖 Jackson 对 Instant 的默认输出（整秒时会省略毫秒）。
 */
public final class ApiTime {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private ApiTime() {
    }

    public static String format(Instant instant) {
        return instant == null ? null : FORMAT.format(instant);
    }
}
