package com.xiaoying.storage;

import java.nio.file.Path;

/** 已确认属于当前用户、且在磁盘上存在的文件：绝对路径（来自数据库中的 storage_key）与图片类型。 */
public record UserFile(Path path, ImageType type) {
}
