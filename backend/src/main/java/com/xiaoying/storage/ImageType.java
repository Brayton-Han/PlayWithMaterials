package com.xiaoying.storage;

import java.util.Optional;

/**
 * 服务端认可的图片类型（OQ-17 临时默认：jpeg / png / webp，与 api.yaml {@code createTask} 的 files encoding 一致）。
 * 实际类型由 {@link ImageTypeDetector} 按文件头魔数判定；存储时的扩展名由类型决定，不使用用户提供的文件名。
 */
public enum ImageType {

    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    private final String mimeType;
    private final String extension;

    ImageType(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    /** 不带点的小写扩展名。 */
    public String extension() {
        return extension;
    }

    /** 按 MIME 类型查找（忽略大小写与参数，如 {@code image/png; charset=x}）；不认识的类型返回空。 */
    public static Optional<ImageType> fromMimeType(String mimeType) {
        if (mimeType == null) {
            return Optional.empty();
        }
        String base = mimeType.split(";", 2)[0].trim();
        for (ImageType t : values()) {
            if (t.mimeType.equalsIgnoreCase(base)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
