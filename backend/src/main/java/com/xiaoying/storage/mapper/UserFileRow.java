package com.xiaoying.storage.mapper;

/**
 * 归属查询的结果：数据库中记录的存储路径（相对存储根目录）与 MIME 类型。
 * 读文件时只使用这里的 {@code storageKey}，不拼接请求路径（architecture §4.3.1）。
 * 结果文件没有单独的 MIME 列，{@code mimeType} 为 null，由扩展名（服务端按魔数生成）推出。
 */
public record UserFileRow(String storageKey, String mimeType) {
}
