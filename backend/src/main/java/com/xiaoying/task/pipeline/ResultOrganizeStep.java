package com.xiaoying.task.pipeline;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.xiaoying.catalog.domain.Template;
import com.xiaoying.catalog.service.CatalogService;
import com.xiaoying.storage.FileStorageService;
import com.xiaoying.storage.ImageType;
import com.xiaoying.storage.ImageTypeDetector;
import com.xiaoying.storage.StorageException;
import com.xiaoying.storage.StoredFile;
import com.xiaoying.task.domain.FailureCode;
import com.xiaoying.task.domain.TaskStage;

/**
 * 结果整理（F-06 规则 1.3，architecture §5.2）[OQ-08 临时默认 A]：
 * {@link SimulationGate} 之后，把模板的预置结果图（{@code templates.sample_result_path}，classpath 相对路径）
 * 复制为任务专属文件 {@code results/{taskId}/{uuid}.{ext}}（扩展名按魔数类型），
 * 并通过 {@link TaskContext#setResultUrl} 返回 {@code /files/results/{taskId}/{uuid}.{ext}}；
 * pipeline 在本步成功后写入 {@code tasks.result_url}。
 * 预置图缺失 / 不是可识别的图片 / 写盘失败 → {@code RESULT_WRITE_FAILED}。
 */
@Component
public class ResultOrganizeStep implements TaskStep {

    static final String REASON_WRITE_FAILED = "结果文件生成失败";

    private final SimulationGate gate;
    private final CatalogService catalog;
    private final FileStorageService storage;

    public ResultOrganizeStep(SimulationGate gate, CatalogService catalog, FileStorageService storage) {
        this.gate = gate;
        this.catalog = catalog;
        this.storage = storage;
    }

    @Override
    public TaskStage stage() {
        return TaskStage.RESULT_ORGANIZING;
    }

    @Override
    public void execute(TaskContext context) throws InterruptedException {
        gate.check(stage(), context);

        Template template = catalog.getTemplate(context.templateId());
        Resource sample = sampleResource(template.getSampleResultPath());
        StoredFile result;
        try (InputStream in = new BufferedInputStream(sample.getInputStream())) {
            in.mark(ImageTypeDetector.HEADER_LENGTH);
            ImageType type = ImageTypeDetector.detect(in).orElseThrow(() -> new StepFailedException(
                    FailureCode.RESULT_WRITE_FAILED, REASON_WRITE_FAILED,
                    new IllegalStateException("sample result is not a supported image: " + sample)));
            in.reset();
            result = storage.storeResult(context.taskId(), in, type);
        } catch (IOException | StorageException e) {
            throw new StepFailedException(FailureCode.RESULT_WRITE_FAILED, REASON_WRITE_FAILED, e);
        }
        context.setResultUrl(result.url());
    }

    private static Resource sampleResource(String path) {
        if (path == null || path.isBlank() || path.contains("..")) {
            throw new StepFailedException(FailureCode.RESULT_WRITE_FAILED, REASON_WRITE_FAILED,
                    new IllegalStateException("invalid sample_result_path: " + path));
        }
        return new ClassPathResource(path);
    }
}
