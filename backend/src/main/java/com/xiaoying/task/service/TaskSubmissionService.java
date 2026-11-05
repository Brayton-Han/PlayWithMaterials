package com.xiaoying.task.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.xiaoying.catalog.domain.Template;
import com.xiaoying.catalog.service.CatalogService;
import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;
import com.xiaoying.common.error.FieldErrorDto;
import com.xiaoying.simulation.SimulationSettings;
import com.xiaoying.storage.FileStorageService;
import com.xiaoying.storage.ImageType;
import com.xiaoying.storage.ImageTypeDetector;
import com.xiaoying.storage.StoredFile;
import com.xiaoying.storage.UploadBatch;
import com.xiaoying.task.domain.TaskView;
import com.xiaoying.task.mapper.TaskMapper;
import com.xiaoying.task.pipeline.TaskDispatcher;

/**
 * 任务提交与受理（F-03 ~ F-05，architecture §5.1，api.yaml createTask）。
 *
 * <ol>
 *   <li>模板存在 → 否则 404 {@code TEMPLATE_NOT_FOUND}；</li>
 *   <li>文件数量 = {@code requiredImageCount} [OQ-04 临时默认：恰好 N 张] → 否则 400 {@code IMAGE_COUNT_MISMATCH}；</li>
 *   <li>逐个校验非空、大小 ≤ {@code maxFileSizeBytes}、声明的 Content-Type 与文件头魔数类型都 ∈ {@code acceptedMimeTypes}
 *       [OQ-17 临时默认：双重校验] → 否则 400 {@code ASSET_VALIDATION_FAILED}（每个出错的上传位一条，带 slotIndex）；</li>
 *   <li>文件暂存到 {@code tmp/} → {@link TaskWriter#create} 事务写入任务（PENDING，快照模拟配置 [OQ-10]）与素材；</li>
 *   <li>事务提交后 commit 文件到正式目录，再派发执行器；</li>
 *   <li>派发被拒绝 → 撤销任务与文件，503 {@code QUEUE_FULL} [OQ-12]。</li>
 * </ol>
 *
 * 本方法<b>不加事务</b>：事务只包住第 4 步，保证执行器线程读到的一定是已提交的数据。
 * 任何失败都不会留下任务记录或文件（校验失败时尚未写任何文件）。
 */
@Service
public class TaskSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(TaskSubmissionService.class);

    static final int ORIGINAL_NAME_MAX = 255;

    private final CatalogService catalogService;
    private final FileStorageService storage;
    private final TaskWriter writer;
    private final TaskMapper taskMapper;
    private final TaskDispatcher dispatcher;
    private final SimulationSettings simulation;

    public TaskSubmissionService(CatalogService catalogService, FileStorageService storage, TaskWriter writer,
                                 TaskMapper taskMapper, TaskDispatcher dispatcher, SimulationSettings simulation) {
        this.catalogService = catalogService;
        this.storage = storage;
        this.writer = writer;
        this.taskMapper = taskMapper;
        this.dispatcher = dispatcher;
        this.simulation = simulation;
    }

    /**
     * @param files 按上传位顺序，第 i 个文件对应 slotIndex = i；null 视为 0 个
     * @return 刚创建的任务（PENDING）
     */
    public TaskView submit(long userId, String templateId, List<MultipartFile> files) {
        Template template = catalogService.getTemplate(templateId);
        List<MultipartFile> parts = files == null ? List.of() : files;
        checkCount(template, parts.size());
        List<ImageType> types = validateFiles(template, parts);

        UploadBatch batch = storage.newUploadBatch();
        TaskView view;
        try {
            List<NewAsset> assets = new ArrayList<>(parts.size());
            for (int i = 0; i < parts.size(); i++) {
                MultipartFile part = parts.get(i);
                StoredFile stored;
                try (InputStream in = part.getInputStream()) {
                    stored = batch.stage(in, types.get(i));
                }
                assets.add(new NewAsset(i, stored, originalName(part)));
            }
            view = writer.create(userId, template.getId(), simulation.snapshotJson(), assets);
        } catch (IOException e) {
            batch.rollback();
            throw new UncheckedIOException("failed to read uploaded file", e);
        } catch (RuntimeException e) {
            batch.rollback();
            throw e;
        }

        long taskId = view.task().getId();
        try {
            batch.commit();
        } catch (RuntimeException e) {
            withdraw(taskId, batch);
            throw e;
        }

        try {
            dispatcher.dispatch(taskId);
        } catch (TaskRejectedException e) {
            log.warn("task {} rejected by executor, withdrawn: {}", taskId, e.getMessage());
            withdraw(taskId, batch);
            throw new ApiException(ErrorCode.QUEUE_FULL);
        } catch (RuntimeException e) {
            withdraw(taskId, batch);
            throw e;
        }
        log.info("task {} accepted: user={}, template={}, files={}", taskId, userId, template.getId(), parts.size());
        return view;
    }

    // ---------- 校验（全部在写文件之前） ----------

    private static void checkCount(Template template, int actual) {
        int expected = template.getRequiredImageCount();
        if (actual != expected) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("expected", expected);
            details.put("actual", actual);
            throw new ApiException(ErrorCode.IMAGE_COUNT_MISMATCH,
                    "该模板需要上传 %d 张图片".formatted(expected), details);
        }
    }

    /** 返回每个文件由魔数判定的真实类型；有任何不合格的文件时抛 400，列出每个出错的上传位。 */
    private static List<ImageType> validateFiles(Template template, List<MultipartFile> parts) {
        Set<ImageType> accepted = acceptedTypes(template);
        List<ImageType> types = new ArrayList<>(parts.size());
        List<FieldErrorDto> errors = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            MultipartFile part = parts.get(i);
            int n = i + 1;
            if (part.isEmpty()) {
                errors.add(fileError(i, "FILE_EMPTY", "第 %d 张图片是空文件".formatted(n)));
                types.add(null);
                continue;
            }
            if (part.getSize() > template.getMaxFileSizeBytes()) {
                errors.add(fileError(i, "FILE_TOO_LARGE", "第 %d 张图片超过 %s 上限"
                        .formatted(n, formatSize(template.getMaxFileSizeBytes()))));
                types.add(null);
                continue;
            }
            Optional<ImageType> declared = ImageType.fromMimeType(part.getContentType());
            Optional<ImageType> detected = detect(part);
            if (declared.filter(accepted::contains).isEmpty() || detected.filter(accepted::contains).isEmpty()) {
                errors.add(fileError(i, "FILE_TYPE_NOT_ACCEPTED", "第 %d 张图片格式不支持，仅支持 %s"
                        .formatted(n, displayNames(accepted))));
                types.add(null);
                continue;
            }
            types.add(detected.get());
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.ASSET_VALIDATION_FAILED, ErrorCode.ASSET_VALIDATION_FAILED.defaultMessage(),
                    Map.of("fieldErrors", errors));
        }
        return types;
    }

    private static Optional<ImageType> detect(MultipartFile part) {
        try (InputStream in = part.getInputStream()) {
            return ImageTypeDetector.detect(in);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read uploaded file", e);
        }
    }

    /** 模板配置中不认识的类型被忽略（只认 {@link ImageType} 中的 jpeg / png / webp）。 */
    private static Set<ImageType> acceptedTypes(Template template) {
        Set<ImageType> set = EnumSet.noneOf(ImageType.class);
        for (String mime : template.getAcceptedMimeTypes()) {
            ImageType.fromMimeType(mime).ifPresent(set::add);
        }
        return set;
    }

    private static FieldErrorDto fileError(int slotIndex, String code, String message) {
        return new FieldErrorDto("files", slotIndex, code, message);
    }

    static String displayNames(Set<ImageType> types) {
        return types.stream()
                .map(t -> t == ImageType.WEBP ? "WebP" : t.name())
                .collect(Collectors.joining("、"));
    }

    /** 5242880 → "5 MB"；1048576 → "1 MB"；不是整 MB 时保留一位小数。 */
    static String formatSize(long bytes) {
        long mb = 1024L * 1024L;
        if (bytes % mb == 0) {
            return (bytes / mb) + " MB";
        }
        if (bytes >= mb) {
            return "%.1f MB".formatted(bytes / (double) mb);
        }
        return Math.max(1, bytes / 1024) + " KB";
    }

    /** 原始文件名只用于排查（database.md §2.6），截断到 255 个字符（按码点，不拆开代理对）；缺失时为空串。 */
    static String originalName(MultipartFile part) {
        String name = part.getOriginalFilename();
        if (name == null) {
            return "";
        }
        if (name.codePointCount(0, name.length()) <= ORIGINAL_NAME_MAX) {
            return name;
        }
        return name.substring(0, name.offsetByCodePoints(0, ORIGINAL_NAME_MAX));
    }

    // ---------- 补偿 ----------

    /**
     * 事务已提交之后的失败（文件 commit 失败、派发失败）：删除仍为 PENDING 的任务（素材级联删除），再删除本批文件。
     * 任务删不掉时（已被执行器领取，或数据库异常）保留文件，绝不删除仍被数据库记录引用的文件。
     */
    private void withdraw(long taskId, UploadBatch batch) {
        boolean removed;
        try {
            removed = taskMapper.deleteById(taskId) == 1;
        } catch (RuntimeException e) {
            log.error("failed to withdraw task {}", taskId, e);
            removed = false;
        }
        if (removed) {
            batch.rollback();
        } else {
            log.error("task {} could not be withdrawn; its files are kept", taskId);
        }
    }
}
