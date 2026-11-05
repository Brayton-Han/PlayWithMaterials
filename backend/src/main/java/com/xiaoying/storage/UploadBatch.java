package com.xiaoying.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次提交的一批上传文件（T-030 两阶段写入）。
 *
 * <ol>
 *   <li>{@link #stage}：写入 {@code tmp/{uuid}.{ext}.part}，返回 commit 后的 storageKey 与 URL；</li>
 *   <li>{@link #commit()}：全部移到 {@code uploads/yyyy/MM/{uuid}.{ext}}（同一文件系统内原子移动）；</li>
 *   <li>{@link #rollback()}：删除本批所有临时文件，以及已 commit 的正式文件（用于 commit 之后的补偿，
 *       如派发时 503 QUEUE_FULL）。幂等，不抛异常。</li>
 * </ol>
 *
 * commit 中途失败时抛 {@link StorageException}，调用方应再调用 {@link #rollback()} 清理。
 * 非线程安全。
 */
public final class UploadBatch {

    private static final Logger log = LoggerFactory.getLogger(UploadBatch.class);

    private enum State { OPEN, COMMITTED, ROLLED_BACK }

    private record Entry(Path tempPath, Path finalPath, StoredFile file) {
    }

    private final Path root;
    private final Path tmpDir;
    private final String uploadsDir;
    private final Clock clock;
    private final List<Entry> entries = new ArrayList<>();
    private State state = State.OPEN;

    UploadBatch(Path root, Path tmpDir, String uploadsDir, Clock clock) {
        this.root = root;
        this.tmpDir = tmpDir;
        this.uploadsDir = uploadsDir;
        this.clock = clock;
    }

    /**
     * 把文件内容写入临时目录。{@code type} 应是调用方事先用 {@link ImageTypeDetector} 判定的真实类型，决定扩展名。
     * 流由调用方关闭。
     */
    public StoredFile stage(InputStream content, ImageType type) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(type, "type");
        requireState(State.OPEN, "stage");

        String name = UUID.randomUUID() + "." + type.extension();
        YearMonth ym = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        String storageKey = "%s/%04d/%02d/%s".formatted(uploadsDir, ym.getYear(), ym.getMonthValue(), name);
        Path tempPath = tmpDir.resolve(name + ".part");
        Path finalPath = root.resolve(storageKey);

        long size;
        try {
            Files.createDirectories(tmpDir);
            size = Files.copy(content, tempPath);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tempPath);
            throw new StorageException("failed to stage upload", e);
        }
        StoredFile file = new StoredFile(storageKey, FileStorageService.FILES_URL_PREFIX + storageKey, type, size);
        entries.add(new Entry(tempPath, finalPath, file));
        return file;
    }

    /** 把所有暂存文件移到正式目录。之后这些文件可以通过 {@link StoredFile#url()} 访问（经 T-047 鉴权）。 */
    public void commit() {
        requireState(State.OPEN, "commit");
        for (Entry e : entries) {
            if (Files.exists(e.finalPath()) && !Files.exists(e.tempPath())) {
                continue; // 上一次 commit 中途失败前已移动
            }
            try {
                Files.createDirectories(e.finalPath().getParent());
                move(e.tempPath(), e.finalPath());
            } catch (IOException | RuntimeException ex) {
                throw new StorageException("failed to commit upload " + e.file().storageKey(), ex);
            }
        }
        state = State.COMMITTED;
    }

    /** 删除本批写入的所有文件（临时与正式）。可在任何阶段调用，重复调用无副作用。 */
    public void rollback() {
        if (state == State.ROLLED_BACK) {
            return;
        }
        for (Entry e : entries) {
            deleteQuietly(e.tempPath());
            deleteQuietly(e.finalPath());
        }
        state = State.ROLLED_BACK;
    }

    /** 本批已暂存的文件，按 stage 顺序。 */
    public List<StoredFile> files() {
        return entries.stream().map(Entry::file).toList();
    }

    public boolean isCommitted() {
        return state == State.COMMITTED;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private void requireState(State expected, String op) {
        if (state != expected) {
            throw new IllegalStateException("cannot " + op + " an upload batch in state " + state);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("failed to delete {}: {}", p, e.toString());
        }
    }
}
