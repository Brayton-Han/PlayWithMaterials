package com.xiaoying.task.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.xiaoying.catalog.domain.Template;
import com.xiaoying.catalog.service.CatalogService;
import com.xiaoying.storage.FileStorageService;
import com.xiaoying.storage.ImageType;
import com.xiaoying.storage.ImageTypeDetector;
import com.xiaoying.task.domain.FailureCode;
import com.xiaoying.task.domain.TaskAsset;
import com.xiaoying.task.domain.TaskStage;

/**
 * 素材检查（F-06 规则 1.1，architecture §5.2）[OQ-14 临时默认：轻量真实检查 + 模拟延迟 / 失败]：
 * <ol>
 *   <li>{@link SimulationGate}：模拟耗时、指定阶段失败；</li>
 *   <li>素材记录数 = 模板要求张数，且上传位为 0..n-1，否则 {@code ASSET_MISSING}；</li>
 *   <li>每个文件（只按数据库中的 storage_key 定位）存在、是普通文件、可读，否则 {@code ASSET_MISSING}；</li>
 *   <li>文件大小与记录一致、不超过模板上限；魔数类型可识别、在模板允许列表内、与记录的 mime_type 一致，
 *       否则 {@code ASSET_INVALID}。</li>
 * </ol>
 * 用户可读原因用中文，上传位按"第 N 张"（从 1 开始）描述 [OQ-11]；技术细节只写日志（作为 cause）。
 */
@Component
public class AssetCheckStep implements TaskStep {

    static final String REASON_COUNT_MISMATCH = "素材数量与模板要求不一致";

    private final SimulationGate gate;
    private final CatalogService catalog;
    private final FileStorageService storage;

    public AssetCheckStep(SimulationGate gate, CatalogService catalog, FileStorageService storage) {
        this.gate = gate;
        this.catalog = catalog;
        this.storage = storage;
    }

    @Override
    public TaskStage stage() {
        return TaskStage.ASSET_CHECK;
    }

    @Override
    public void execute(TaskContext context) throws InterruptedException {
        gate.check(stage(), context);

        Template template = catalog.getTemplate(context.templateId());
        List<TaskAsset> assets = context.assets();
        if (assets.size() != template.getRequiredImageCount()) {
            throw new StepFailedException(FailureCode.ASSET_MISSING, REASON_COUNT_MISMATCH,
                    new IllegalStateException("expected %d assets, found %d"
                            .formatted(template.getRequiredImageCount(), assets.size())));
        }
        for (int i = 0; i < assets.size(); i++) {
            if (assets.get(i).getSlotIndex() != i) {   // 素材按 slotIndex 升序加载
                throw new StepFailedException(FailureCode.ASSET_MISSING, REASON_COUNT_MISMATCH,
                        new IllegalStateException("slot " + i + " has no asset"));
            }
        }
        for (TaskAsset asset : assets) {
            checkFile(asset, template);
        }
    }

    private void checkFile(TaskAsset asset, Template template) {
        String slot = "第 " + (asset.getSlotIndex() + 1) + " 张素材";
        Path path;
        try {
            path = storage.resolve(asset.getStorageKey());
        } catch (IllegalArgumentException e) {
            throw new StepFailedException(FailureCode.ASSET_INVALID, slot + "存储路径无效", e);
        }
        if (!Files.isRegularFile(path)) {
            throw new StepFailedException(FailureCode.ASSET_MISSING, slot + "文件缺失",
                    new IllegalStateException("not a regular file: " + asset.getStorageKey()));
        }

        long size;
        Optional<ImageType> type;
        try (InputStream in = Files.newInputStream(path)) {
            size = Files.size(path);
            type = ImageTypeDetector.detect(in);
        } catch (IOException e) {
            throw new StepFailedException(FailureCode.ASSET_MISSING, slot + "文件无法读取", e);
        }

        if (size != asset.getSizeBytes()) {
            throw new StepFailedException(FailureCode.ASSET_INVALID, slot + "文件大小与记录不一致",
                    new IllegalStateException("size %d != recorded %d".formatted(size, asset.getSizeBytes())));
        }
        if (size <= 0 || size > template.getMaxFileSizeBytes()) {
            throw new StepFailedException(FailureCode.ASSET_INVALID, slot + "文件大小不符合模板要求",
                    new IllegalStateException("size %d, limit %d".formatted(size, template.getMaxFileSizeBytes())));
        }
        String detected = type.map(ImageType::mimeType).orElse(null);
        if (detected == null || !template.getAcceptedMimeTypes().contains(detected)
                || !detected.equals(asset.getMimeType())) {
            throw new StepFailedException(FailureCode.ASSET_INVALID, slot + "文件类型不符合模板要求",
                    new IllegalStateException("detected %s, recorded %s, accepted %s".formatted(
                            detected, asset.getMimeType(), template.getAcceptedMimeTypes())));
        }
    }
}
