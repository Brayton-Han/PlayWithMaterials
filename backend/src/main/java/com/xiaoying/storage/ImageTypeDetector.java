package com.xiaoying.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;

/**
 * 按文件头魔数识别真实图片类型（OQ-17）。不看扩展名，也不看请求声明的 Content-Type：
 * 改了扩展名的非图片文件识别结果为空。
 *
 * <ul>
 *   <li>JPEG：{@code FF D8 FF}</li>
 *   <li>PNG：{@code 89 50 4E 47 0D 0A 1A 0A}</li>
 *   <li>WebP：{@code "RIFF" ???? "WEBP"}</li>
 * </ul>
 */
public final class ImageTypeDetector {

    /** 判定所需的最多字节数。 */
    public static final int HEADER_LENGTH = 12;

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};

    private ImageTypeDetector() {
    }

    /** 读取流的前 {@link #HEADER_LENGTH} 个字节判定类型；调用方负责关闭流。 */
    public static Optional<ImageType> detect(InputStream in) throws IOException {
        return detect(in.readNBytes(HEADER_LENGTH));
    }

    /** 按给定的文件头判定类型；长度不足或不匹配时返回空。 */
    public static Optional<ImageType> detect(byte[] header) {
        if (header == null) {
            return Optional.empty();
        }
        if (startsWith(header, 0, JPEG)) {
            return Optional.of(ImageType.JPEG);
        }
        if (startsWith(header, 0, PNG)) {
            return Optional.of(ImageType.PNG);
        }
        if (startsWith(header, 0, RIFF) && startsWith(header, 8, WEBP)) {
            return Optional.of(ImageType.WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int offset, byte[] magic) {
        return data.length >= offset + magic.length
                && Arrays.equals(data, offset, offset + magic.length, magic, 0, magic.length);
    }
}
