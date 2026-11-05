package com.xiaoying.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.xiaoying.storage.mapper.UserFileMapper;
import com.xiaoying.storage.mapper.UserFileRow;

/**
 * 用户素材与结果图的读取（DQ-02，architecture §4.3.1，T-047）。
 * <ul>
 *   <li>归属按 database.md Q13 / Q14 查询，都带 {@code userId}；不属于该用户的、不存在的一律返回空（→ 404）。</li>
 *   <li>实际读取的路径只来自数据库中的 storage_key / result_url，经 {@link FileStorageService#resolve} 限制在存储根目录内。</li>
 *   <li>已删除账户没有有效会话（请求在拦截器即被 401），其他用户查不到归属，因此删除后链接失效；文件本身保留（OQ-26）。</li>
 * </ul>
 * 调用方负责先校验路径参数格式（api.yaml 的 pattern），这里只接收格式正确的值。
 */
@Service
public class UserFileService {

    private static final Logger log = LoggerFactory.getLogger(UserFileService.class);

    private final UserFileMapper mapper;
    private final FileStorageService storage;

    public UserFileService(UserFileMapper mapper, FileStorageService storage) {
        this.mapper = mapper;
        this.storage = storage;
    }

    /** {@code /files/uploads/{yyyy}/{mm}/{fileName}}。 */
    public Optional<UserFile> findUpload(long userId, String yyyy, String mm, String fileName) {
        String requestedKey = "%s/%s/%s/%s".formatted(FileStorageService.UPLOADS_DIR, yyyy, mm, fileName);
        UserFileRow row = mapper.findUpload(userId, requestedKey);
        if (row == null) {
            return Optional.empty();
        }
        Optional<ImageType> type = ImageType.fromMimeType(row.mimeType());
        return open(row.storageKey(), type.isPresent() ? type : typeByExtension(row.storageKey()));
    }

    /** {@code /files/results/{taskId}/{fileName}}：请求路径必须与该任务的 result_url 完全一致。 */
    public Optional<UserFile> findResult(long userId, long taskId, String fileName) {
        String requestedUrl = "%s%s/%d/%s".formatted(
                FileStorageService.FILES_URL_PREFIX, FileStorageService.RESULTS_DIR, taskId, fileName);
        String resultUrl = mapper.findResultUrl(userId, taskId, requestedUrl);
        if (resultUrl == null || !resultUrl.startsWith(FileStorageService.FILES_URL_PREFIX)) {
            return Optional.empty();
        }
        String storageKey = resultUrl.substring(FileStorageService.FILES_URL_PREFIX.length());
        return open(storageKey, typeByExtension(storageKey));
    }

    private Optional<UserFile> open(String storageKey, Optional<ImageType> type) {
        Path path;
        try {
            path = storage.resolve(storageKey);
        } catch (IllegalArgumentException e) {
            log.warn("Stored file key is invalid: {}", storageKey);
            return Optional.empty();
        }
        if (type.isEmpty()) {
            log.warn("Stored file has no recognised image type: {}", storageKey);
            return Optional.empty();
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            // 有记录但磁盘上没有：对用户而言就是不存在
            log.warn("Stored file is missing on disk: {}", storageKey);
            return Optional.empty();
        }
        return Optional.of(new UserFile(path, type.get()));
    }

    private static Optional<ImageType> typeByExtension(String storageKey) {
        int dot = storageKey.lastIndexOf('.');
        return dot < 0 ? Optional.empty() : ImageType.fromExtension(storageKey.substring(dot + 1));
    }
}
