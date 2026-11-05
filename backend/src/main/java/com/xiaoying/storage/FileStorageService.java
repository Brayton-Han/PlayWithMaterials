package com.xiaoying.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 本地文件存储（architecture §3 storage 模块、database.md §6，T-030）。供任务提交（T-041）调用，不对外暴露上传接口。
 *
 * <pre>
 * ${app.storage.root}/
 * ├── tmp/{uuid}.{ext}.part          # 暂存，commit 前
 * ├── uploads/yyyy/MM/{uuid}.{ext}   # 正式目录（task_assets.storage_key）
 * └── results/{taskId}/{uuid}.{ext}  # 结果文件（tasks.result_url，T-043）
 * </pre>
 *
 * <p>两阶段写入：{@link #newUploadBatch()} → {@link UploadBatch#stage} 写入临时目录 →
 * 事务提交后 {@link UploadBatch#commit()} 移到正式目录；任何一步失败调用 {@link UploadBatch#rollback()} 删除本批文件。
 * 文件名一律由服务端生成（UUID + 由魔数类型决定的扩展名），不使用用户提供的文件名，杜绝路径穿越。
 * 年月目录按 UTC 计算。
 */
@Service
public class FileStorageService {

    /** 用户文件 URL 前缀：URL = {@code /files/} + storageKey（database.md §2.6）。 */
    public static final String FILES_URL_PREFIX = "/files/";

    static final String TMP_DIR = "tmp";
    static final String UPLOADS_DIR = "uploads";
    static final String RESULTS_DIR = "results";

    private final Path root;
    private final Clock clock;

    @Autowired
    public FileStorageService(StorageProperties properties) {
        this(properties, Clock.systemUTC());
    }

    FileStorageService(StorageProperties properties, Clock clock) {
        this.root = properties.root().toAbsolutePath().normalize();
        this.clock = clock;
    }

    /** 开始一批上传（一次任务提交对应一批）。批次对象不是线程安全的，只在当前请求线程中使用。 */
    public UploadBatch newUploadBatch() {
        return new UploadBatch(root, root.resolve(TMP_DIR), UPLOADS_DIR, clock);
    }

    /**
     * 写入一个任务的结果文件（T-043 结果整理，database.md §6）：{@code results/{taskId}/{uuid}.{ext}}。
     * 先写同目录下的 {@code .part}，写完后原子移动到正式文件名，所以正式路径上不会出现写了一半的文件。
     * 文件名由服务端生成，扩展名由 {@code type} 决定。流由调用方关闭。
     *
     * @throws StorageException 写入失败；此时不会留下 {@code .part} 或正式文件
     */
    public StoredFile storeResult(long taskId, InputStream content, ImageType type) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(type, "type");
        if (taskId <= 0) {
            throw new IllegalArgumentException("invalid taskId");
        }
        String storageKey = "%s/%d/%s.%s".formatted(RESULTS_DIR, taskId, UUID.randomUUID(), type.extension());
        Path finalPath = resolve(storageKey);
        Path partPath = finalPath.resolveSibling(finalPath.getFileName() + ".part");
        try {
            Files.createDirectories(finalPath.getParent());
            long size = Files.copy(content, partPath);
            try {
                Files.move(partPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partPath, finalPath);
            }
            return new StoredFile(storageKey, urlOf(storageKey), type, size);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(partPath);
            deleteQuietly(finalPath);
            throw new StorageException("failed to store result for task " + taskId, e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 尽力清理；调用方已经会收到 StorageException
        }
    }

    /** storageKey → 站内可访问 URL（{@code /files/uploads/yyyy/MM/{uuid}.ext}）。访问控制见 {@code UserFileController}。 */
    public String urlOf(String storageKey) {
        checkKey(storageKey);
        return FILES_URL_PREFIX + storageKey;
    }

    /**
     * storageKey → 磁盘绝对路径（T-047 读取文件用）。storageKey 必须来自数据库；
     * 即便如此仍拒绝绝对路径、{@code ..} 等逃出存储根目录的值。
     *
     * @throws IllegalArgumentException storageKey 不合法
     */
    public Path resolve(String storageKey) {
        checkKey(storageKey);
        Path p = root.resolve(storageKey).normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            throw new IllegalArgumentException("storageKey escapes storage root");
        }
        return p;
    }

    Path root() {
        return root;
    }

    private static void checkKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || storageKey.startsWith("/") || storageKey.contains("\\")
                || storageKey.contains("\0")) {
            throw new IllegalArgumentException("invalid storageKey");
        }
        for (String segment : storageKey.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("invalid storageKey");
            }
        }
    }
}
