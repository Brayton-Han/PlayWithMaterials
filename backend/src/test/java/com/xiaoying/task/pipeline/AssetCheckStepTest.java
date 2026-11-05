package com.xiaoying.task.pipeline;

import static com.xiaoying.task.pipeline.StepFixtures.TASK_ID;
import static com.xiaoying.task.pipeline.StepFixtures.TEMPLATE_ID;
import static com.xiaoying.task.pipeline.StepFixtures.context;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xiaoying.catalog.service.CatalogService;
import com.xiaoying.simulation.SimulationConfig;
import com.xiaoying.storage.FileStorageService;
import com.xiaoying.storage.StorageProperties;
import com.xiaoying.task.domain.FailureCode;
import com.xiaoying.task.domain.TaskAsset;
import com.xiaoying.task.domain.TaskStage;

/** T-043 / OQ-14：素材检查——记录数、文件存在 / 可读、大小与类型复核；失败码与用户可读原因。 */
class AssetCheckStepTest {

    private static final long MAX = 1024;

    @TempDir
    Path root;

    CatalogService catalog;
    AssetCheckStep step;

    @BeforeEach
    void setUp() {
        catalog = mock(CatalogService.class);
        when(catalog.getTemplate(TEMPLATE_ID))
                .thenReturn(StepFixtures.template(2, List.of("image/jpeg", "image/png"), MAX, "seed/results/postcard.png"));
        step = new AssetCheckStep(new SimulationGate(ms -> { }), catalog,
                new FileStorageService(new StorageProperties(root)));
    }

    @Test
    void passesWhenEveryAssetIsPresentAndMatchesItsRecord() throws IOException {
        List<TaskAsset> assets = List.of(asset(0, "a.jpg", jpeg(100), "image/jpeg"), asset(1, "b.png", png(200), "image/png"));

        assertThatCode(() -> step.execute(context(StepFixtures.NO_SIMULATION, assets))).doesNotThrowAnyException();
        assertThat(step.stage()).isEqualTo(TaskStage.ASSET_CHECK);
    }

    @Test
    void exactlyTheTemplateSizeLimitPasses() throws IOException {
        List<TaskAsset> assets = List.of(asset(0, "a.jpg", jpeg((int) MAX), "image/jpeg"),
                asset(1, "b.png", png(1), "image/png"));
        assertThatCode(() -> step.execute(context(StepFixtures.NO_SIMULATION, assets))).doesNotThrowAnyException();
    }

    // ---------- ASSET_MISSING ----------

    @Test
    void missingFileFailsWithAssetMissing() throws IOException {
        TaskAsset b = asset(1, "b.png", png(200), "image/png");
        List<TaskAsset> assets = List.of(asset(0, "a.jpg", jpeg(100), "image/jpeg"), b);
        Files.delete(root.resolve(b.getStorageKey()));

        expectFailure(assets, FailureCode.ASSET_MISSING, "第 2 张素材文件缺失");
    }

    @Test
    void directoryInsteadOfFileFailsWithAssetMissing() throws IOException {
        TaskAsset a = asset(0, "a.jpg", jpeg(100), "image/jpeg");
        Path p = root.resolve(a.getStorageKey());
        Files.delete(p);
        Files.createDirectory(p);

        expectFailure(List.of(a, asset(1, "b.png", png(200), "image/png")), FailureCode.ASSET_MISSING,
                "第 1 张素材文件缺失");
    }

    @Test
    void fewerAssetRecordsThanTheTemplateRequiresFailsWithAssetMissing() throws IOException {
        expectFailure(List.of(asset(0, "a.jpg", jpeg(100), "image/jpeg")), FailureCode.ASSET_MISSING,
                AssetCheckStep.REASON_COUNT_MISMATCH);
    }

    @Test
    void noAssetRecordsFailsWithAssetMissing() {
        expectFailure(List.of(), FailureCode.ASSET_MISSING, AssetCheckStep.REASON_COUNT_MISMATCH);
    }

    @Test
    void gapInSlotIndexesFailsWithAssetMissing() throws IOException {
        List<TaskAsset> assets = List.of(asset(0, "a.jpg", jpeg(100), "image/jpeg"), asset(2, "c.png", png(200), "image/png"));
        expectFailure(assets, FailureCode.ASSET_MISSING, AssetCheckStep.REASON_COUNT_MISMATCH);
    }

    // ---------- ASSET_INVALID ----------

    @Test
    void sizeDifferentFromTheRecordFailsWithAssetInvalid() throws IOException {
        TaskAsset b = asset(1, "b.png", png(200), "image/png");
        Files.write(root.resolve(b.getStorageKey()), png(150));   // 文件被截断

        expectFailure(List.of(asset(0, "a.jpg", jpeg(100), "image/jpeg"), b), FailureCode.ASSET_INVALID,
                "第 2 张素材文件大小与记录不一致");
    }

    @Test
    void fileLargerThanTheTemplateLimitFailsWithAssetInvalid() throws IOException {
        TaskAsset a = asset(0, "a.jpg", jpeg((int) MAX + 1), "image/jpeg");   // 记录与文件一致，但超过模板上限
        expectFailure(List.of(a, asset(1, "b.png", png(200), "image/png")), FailureCode.ASSET_INVALID,
                "第 1 张素材文件大小不符合模板要求");
    }

    @Test
    void contentNoLongerAnImageFailsWithAssetInvalid() throws IOException {
        TaskAsset a = asset(0, "a.jpg", jpeg(100), "image/jpeg");
        byte[] text = new byte[100];
        Arrays.fill(text, (byte) 'x');
        Files.write(root.resolve(a.getStorageKey()), text);   // 大小相同，魔数变了

        expectFailure(List.of(a, asset(1, "b.png", png(200), "image/png")), FailureCode.ASSET_INVALID,
                "第 1 张素材文件类型不符合模板要求");
    }

    @Test
    void typeNotAcceptedByTheTemplateFailsWithAssetInvalid() throws IOException {
        TaskAsset b = asset(1, "b.webp", webp(200), "image/webp");
        expectFailure(List.of(asset(0, "a.jpg", jpeg(100), "image/jpeg"), b), FailureCode.ASSET_INVALID,
                "第 2 张素材文件类型不符合模板要求");
    }

    @Test
    void detectedTypeDifferentFromTheRecordedTypeFailsWithAssetInvalid() throws IOException {
        TaskAsset a = asset(0, "a.jpg", png(100), "image/jpeg");   // 记录为 jpeg，实际是 png
        expectFailure(List.of(a, asset(1, "b.png", png(200), "image/png")), FailureCode.ASSET_INVALID,
                "第 1 张素材文件类型不符合模板要求");
    }

    @Test
    void storageKeyEscapingTheRootFailsWithAssetInvalid() throws IOException {
        TaskAsset a = new TaskAsset(TASK_ID, 0, "../outside.jpg", "a.jpg", "image/jpeg", 100);
        expectFailure(List.of(a, asset(1, "b.png", png(200), "image/png")), FailureCode.ASSET_INVALID,
                "第 1 张素材存储路径无效");
    }

    // ---------- 模拟 ----------

    @Test
    void simulatedFailureHappensBeforeAnyRealCheck() {
        SimulationConfig sim = StepFixtures.failAt(TaskStage.ASSET_CHECK, null);

        assertThatThrownBy(() -> step.execute(context(sim, List.of())))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo(FailureCode.SIMULATED_FAILURE);
                    assertThat(e.reason()).isEqualTo("模拟失败：素材检查阶段出错");
                });
        verifyNoInteractions(catalog);
    }

    @Test
    void failStageOfAnotherStageStillRunsTheRealCheck() {
        SimulationConfig sim = StepFixtures.failAt(TaskStage.GENERATING, null);
        assertThatThrownBy(() -> step.execute(context(sim, List.of())))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo(FailureCode.ASSET_MISSING));
    }

    // ---------- helpers ----------

    private void expectFailure(List<TaskAsset> assets, FailureCode code, String reason) {
        assertThatThrownBy(() -> step.execute(context(StepFixtures.NO_SIMULATION, assets)))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo(code);
                    assertThat(e.reason()).isEqualTo(reason);
                    assertThat(e.getCause()).isNotNull();   // 技术细节只放在 cause（写日志）
                });
    }

    /** 写入 uploads/2026/11/{name}，返回与文件一致的素材记录。 */
    private TaskAsset asset(int slot, String name, byte[] content, String recordedMime) throws IOException {
        String key = "uploads/2026/11/" + slot + "-" + name;
        Path p = root.resolve(key);
        Files.createDirectories(p.getParent());
        Files.write(p, content);
        return new TaskAsset(TASK_ID, slot, key, name, recordedMime, content.length);
    }

    static byte[] jpeg(int size) {
        return withHeader(size, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0);
    }

    static byte[] png(int size) {
        return withHeader(size, (byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G', (byte) 0x0D, (byte) 0x0A,
                (byte) 0x1A, (byte) 0x0A);
    }

    static byte[] webp(int size) {
        byte[] h = "RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1);
        return withHeader(size, h);
    }

    private static byte[] withHeader(int size, byte... header) {
        byte[] data = new byte[Math.max(size, header.length)];
        Arrays.fill(data, (byte) 0x42);
        System.arraycopy(header, 0, data, 0, header.length);
        return data;
    }
}
