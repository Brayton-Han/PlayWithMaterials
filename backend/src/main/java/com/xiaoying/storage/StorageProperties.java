package com.xiaoying.storage;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.storage.*}（architecture §7）。
 *
 * @param root 上传与结果文件根目录（容器内 {@code /data/storage}，环境变量 {@code APP_STORAGE_ROOT}）
 */
@ConfigurationProperties("app.storage")
public record StorageProperties(Path root) {

    public StorageProperties {
        if (root == null) {
            root = Path.of("/data/storage");
        }
    }
}
