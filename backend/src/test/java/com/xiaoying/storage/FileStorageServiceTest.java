package com.xiaoying.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T-030：两阶段写入（stage → commit / rollback）、随机文件名、URL 生成与路径穿越防护。 */
class FileStorageServiceTest {

    /** 上海时间已是 12 月，UTC 仍是 11 月：年月目录必须按 UTC。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-30T20:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final String KEY_PATTERN =
            "uploads/2026/11/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.%s";

    @TempDir
    Path root;

    FileStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new FileStorageService(new StorageProperties(root), CLOCK);
    }

    @Test
    void stageWritesOnlyToTempDirectory() throws IOException {
        UploadBatch batch = storage.newUploadBatch();
        StoredFile f = batch.stage(png(), ImageType.PNG);

        assertThat(f.storageKey()).matches(KEY_PATTERN.formatted("png"));
        assertThat(f.url()).isEqualTo("/files/" + f.storageKey());
        assertThat(f.mimeType()).isEqualTo("image/png");
        assertThat(f.sizeBytes()).isEqualTo(ImageTypeDetectorTest.PNG_HEADER.length);
        assertThat(Files.exists(storage.resolve(f.storageKey()))).isFalse();
        assertThat(listFiles(tmpDir())).hasSize(1);
        assertThat(Files.exists(root.resolve("uploads"))).isFalse();
        assertThat(batch.files()).containsExactly(f);
    }

    @Test
    void commitMovesFilesSoTheirUrlResolvesToTheContent() throws IOException {
        UploadBatch batch = storage.newUploadBatch();
        StoredFile a = batch.stage(png(), ImageType.PNG);
        StoredFile b = batch.stage(new ByteArrayInputStream(ImageTypeDetectorTest.JPEG_HEADER), ImageType.JPEG);

        batch.commit();

        assertThat(batch.isCommitted()).isTrue();
        assertThat(listFiles(tmpDir())).isEmpty();
        assertThat(b.storageKey()).matches(KEY_PATTERN.formatted("jpg"));
        // URL → storageKey → 磁盘文件，内容与上传一致（HTTP 访问由 T-047 的 UserFileController 提供）
        assertThat(Files.readAllBytes(storage.resolve(keyOf(a.url())))).isEqualTo(ImageTypeDetectorTest.PNG_HEADER);
        assertThat(Files.readAllBytes(storage.resolve(keyOf(b.url())))).isEqualTo(ImageTypeDetectorTest.JPEG_HEADER);
        assertThat(storage.urlOf(a.storageKey())).isEqualTo(a.url());
    }

    @Test
    void rollbackBeforeCommitLeavesTempDirectoryEmpty() throws IOException {
        UploadBatch batch = storage.newUploadBatch();
        batch.stage(png(), ImageType.PNG);
        batch.stage(png(), ImageType.PNG);
        assertThat(listFiles(tmpDir())).hasSize(2);

        batch.rollback();

        assertThat(listFiles(tmpDir())).isEmpty();
        assertThat(Files.exists(root.resolve("uploads"))).isFalse();
    }

    @Test
    void rollbackAfterCommitDeletesCommittedFiles() {
        UploadBatch batch = storage.newUploadBatch();
        StoredFile f = batch.stage(png(), ImageType.PNG);
        batch.commit();
        Path committed = storage.resolve(f.storageKey());
        assertThat(Files.exists(committed)).isTrue();

        batch.rollback(); // 例如 commit 后派发被拒（503 QUEUE_FULL）的补偿

        assertThat(Files.exists(committed)).isFalse();
    }

    @Test
    void rollbackIsIdempotentAndClosesTheBatch() {
        UploadBatch batch = storage.newUploadBatch();
        batch.stage(png(), ImageType.PNG);
        batch.rollback();
        batch.rollback();

        assertThatThrownBy(() -> batch.stage(png(), ImageType.PNG)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(batch::commit).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotStageOrCommitAgainAfterCommit() {
        UploadBatch batch = storage.newUploadBatch();
        batch.stage(png(), ImageType.PNG);
        batch.commit();

        assertThatThrownBy(() -> batch.stage(png(), ImageType.PNG)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(batch::commit).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyBatchCommitAndRollbackAreNoOps() {
        UploadBatch batch = storage.newUploadBatch();
        batch.commit();
        batch.rollback();
        assertThat(batch.files()).isEmpty();
    }

    @Test
    void fileNamesAreRandomAndUniquePerStage() {
        UploadBatch batch = storage.newUploadBatch();
        StoredFile a = batch.stage(png(), ImageType.PNG);
        StoredFile b = batch.stage(png(), ImageType.PNG);
        assertThat(a.storageKey()).isNotEqualTo(b.storageKey());
    }

    @Test
    void extensionComesFromDetectedTypeOnly() {
        UploadBatch batch = storage.newUploadBatch();
        assertThat(batch.stage(png(), ImageType.WEBP).storageKey()).endsWith(".webp");
        assertThat(batch.stage(png(), ImageType.JPEG).storageKey()).endsWith(".jpg");
    }

    @Test
    void failedStageLeavesNoTempFile() throws IOException {
        UploadBatch batch = storage.newUploadBatch();
        InputStream broken = new InputStream() {
            int n;

            @Override
            public int read() throws IOException {
                if (n++ < 4) {
                    return 'x';
                }
                throw new IOException("connection reset");
            }
        };

        assertThatThrownBy(() -> batch.stage(broken, ImageType.PNG)).isInstanceOf(StorageException.class);
        assertThat(listFiles(tmpDir())).isEmpty();
        assertThat(batch.files()).isEmpty();
    }

    @Test
    void failedCommitCanBeRolledBackCompletely() throws IOException {
        UploadBatch batch = storage.newUploadBatch();
        batch.stage(png(), ImageType.PNG);
        Files.writeString(root.resolve("uploads"), "a regular file blocks the uploads directory");

        assertThatThrownBy(batch::commit).isInstanceOf(StorageException.class);
        assertThat(batch.isCommitted()).isFalse();

        batch.rollback();
        assertThat(listFiles(tmpDir())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc/passwd", "uploads/../../etc/passwd", "/etc/passwd", "uploads//a.png",
            "uploads/./a.png", "uploads\\..\\a.png", "..", ".", " ", "uploads/a.png\0.jpg"})
    void invalidStorageKeysAreRejected(String key) {
        assertThatThrownBy(() -> storage.resolve(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.urlOf(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullStorageKeyIsRejected() {
        assertThatThrownBy(() -> storage.resolve(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validStorageKeyResolvesInsideRoot() {
        assertThat(storage.resolve("uploads/2026/11/a.png")).isEqualTo(storage.root().resolve("uploads/2026/11/a.png"));
    }

    @Test
    void defaultRootIsContainerVolume() {
        assertThat(new StorageProperties(null).root()).isEqualTo(Path.of("/data/storage"));
    }

    private static InputStream png() {
        return new ByteArrayInputStream(ImageTypeDetectorTest.PNG_HEADER);
    }

    private static String keyOf(String url) {
        assertThat(url).startsWith(FileStorageService.FILES_URL_PREFIX);
        return url.substring(FileStorageService.FILES_URL_PREFIX.length());
    }

    private Path tmpDir() {
        return root.resolve(FileStorageService.TMP_DIR);
    }

    private static java.util.List<Path> listFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return java.util.List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        }
    }
}
