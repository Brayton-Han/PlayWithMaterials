package com.xiaoying.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;

/** T-030 / OQ-17：真实类型只看文件头魔数，不看扩展名或声明的 Content-Type。 */
class ImageTypeDetectorTest {

    static final byte[] JPEG_HEADER = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    static final byte[] PNG_HEADER = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    static final byte[] WEBP_HEADER = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

    @Test
    void detectsJpegPngAndWebpByMagicBytes() {
        assertThat(ImageTypeDetector.detect(JPEG_HEADER)).contains(ImageType.JPEG);
        assertThat(ImageTypeDetector.detect(PNG_HEADER)).contains(ImageType.PNG);
        assertThat(ImageTypeDetector.detect(WEBP_HEADER)).contains(ImageType.WEBP);
    }

    @Test
    void textFileWithFakeImageExtensionAndContentTypeIsNotAnImage() throws IOException {
        MockMultipartFile fake = new MockMultipartFile("files", "photo.jpg", "image/jpeg",
                "this is not an image, just text".getBytes(StandardCharsets.UTF_8));
        try (InputStream in = fake.getInputStream()) {
            assertThat(ImageTypeDetector.detect(in)).isEmpty();
        }
    }

    @Test
    void typeFollowsContentNotFileName() throws IOException {
        MockMultipartFile pngNamedJpg = new MockMultipartFile("files", "photo.jpg", "image/jpeg", PNG_HEADER);
        try (InputStream in = pngNamedJpg.getInputStream()) {
            assertThat(ImageTypeDetector.detect(in)).contains(ImageType.PNG);
        }
    }

    @Test
    void otherFormatsAreRejected() {
        byte[] gif = "GIF89a\0\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1);
        byte[] pdf = "%PDF-1.7\n%\0\0\0".getBytes(StandardCharsets.ISO_8859_1);
        byte[] wav = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'A', 'V', 'E'};
        byte[] zip = {'P', 'K', 3, 4, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThat(ImageTypeDetector.detect(gif)).isEmpty();
        assertThat(ImageTypeDetector.detect(pdf)).isEmpty();
        assertThat(ImageTypeDetector.detect(wav)).isEmpty();
        assertThat(ImageTypeDetector.detect(zip)).isEmpty();
    }

    @Test
    void emptyTruncatedAndNullInputsAreRejected() throws IOException {
        assertThat(ImageTypeDetector.detect(new byte[0])).isEmpty();
        assertThat(ImageTypeDetector.detect((byte[]) null)).isEmpty();
        assertThat(ImageTypeDetector.detect(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty();
        assertThat(ImageTypeDetector.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G'})).isEmpty();
        assertThat(ImageTypeDetector.detect(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E'})).isEmpty();
        assertThat(ImageTypeDetector.detect(new ByteArrayInputStream(new byte[0]))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"seed/covers/postcard.jpg", "seed/covers/collage.jpg"})
    void realSeedJpegsAreDetected(String path) throws IOException {
        assertThat(detectResource(path)).contains(ImageType.JPEG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"seed/results/postcard.png", "seed/results/id-photo.png"})
    void realSeedPngsAreDetected(String path) throws IOException {
        assertThat(detectResource(path)).contains(ImageType.PNG);
    }

    @Test
    void mimeTypeLookupIgnoresCaseAndParameters() {
        assertThat(ImageType.fromMimeType("IMAGE/PNG; charset=binary")).contains(ImageType.PNG);
        assertThat(ImageType.fromMimeType("image/jpeg")).contains(ImageType.JPEG);
        assertThat(ImageType.fromMimeType("image/gif")).isEmpty();
        assertThat(ImageType.fromMimeType(null)).isEmpty();
    }

    private static Optional<ImageType> detectResource(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return ImageTypeDetector.detect(in);
        }
    }
}
