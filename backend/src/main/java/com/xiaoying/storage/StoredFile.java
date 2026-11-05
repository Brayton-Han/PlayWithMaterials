package com.xiaoying.storage;

/**
 * 一个已暂存（尚未 commit）的上传文件。{@code storageKey} 是 commit 后在存储根目录下的相对路径
 * （写入 {@code task_assets.storage_key}），{@code url} = {@code /files/} + storageKey（database.md §2.6）。
 * {@code storageKey} 只在服务端使用，不进入 DTO。
 */
public record StoredFile(String storageKey, String url, ImageType type, long sizeBytes) {

    public String mimeType() {
        return type.mimeType();
    }
}
