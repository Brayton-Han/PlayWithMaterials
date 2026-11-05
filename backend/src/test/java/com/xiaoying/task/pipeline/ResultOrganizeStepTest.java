package com.xiaoying.task.pipeline;

import static com.xiaoying.task.pipeline.StepFixtures.TASK_ID;
import static com.xiaoying.task.pipeline.StepFixtures.TEMPLATE_ID;
import static com.xiaoying.task.pipeline.StepFixtures.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import com.xiaoying.catalog.service.CatalogService;
import com.xiaoying.storage.FileStorageService;
import com.xiaoying.storage.StorageProperties;
import com.xiaoying.task.domain.FailureCode;
import com.xiaoying.task.domain.TaskStage;

/** T-043 / OQ-08：结果整理——复制模板预置结果图到 results/{taskId}/，返回指向它的 URL；失败时不留文件。 */
class ResultOrganizeStepTest {

    private static final String URL_PATTERN =
            "^/files/results/42/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png$";

    @TempDir
    Path root;

    CatalogService catalog = mock(CatalogService.class);

    @ParameterizedTest
    @ValueSource(strings = {"seed/results/postcard.png", "seed/results/poster.png", "seed/results/id-photo.png",
            "seed/results/collage.png"})
    void copiesTheTemplateSampleResultToATaskSpecificFileAndReturnsItsUrl(String samplePath) throws Exception {
        TaskContext ctx = context(StepFixtures.NO_SIMULATION, List.of());

        step(samplePath).execute(ctx);

        assertThat(ctx.resultUrl()).matches(URL_PATTERN);
        Path written = root.resolve(ctx.resultUrl().substring("/files/".length()));
        assertThat(written.getParent()).isEqualTo(root.resolve("results").resolve(Long.toString(TASK_ID)));
        assertThat(Files.readAllBytes(written)).isEqualTo(classpathBytes(samplePath));
        assertThat(filesUnder(root.resolve("results"))).containsExactly(written);   // 没有 .part 残留
    }

    @Test
    void eachRunGetsItsOwnFileName() throws Exception {
        ResultOrganizeStep step = step("seed/results/postcard.png");
        TaskContext first = context(StepFixtures.NO_SIMULATION, List.of());
        TaskContext second = context(StepFixtures.NO_SIMULATION, List.of());

        step.execute(first);
        step.execute(second);

        assertThat(first.resultUrl()).isNotEqualTo(second.resultUrl());
        assertThat(filesUnder(root.resolve("results"))).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"seed/results/missing.png", "../seed/results/postcard.png", " "})
    void missingOrInvalidSampleFailsWithResultWriteFailed(String samplePath) throws IOException {
        expectWriteFailed(step(samplePath));
    }

    @Test
    void sampleThatIsNotAnImageFailsWithResultWriteFailed() throws IOException {
        expectWriteFailed(step("application.yml"));
    }

    @Test
    void unwritableStorageFailsWithResultWriteFailed() throws IOException {
        Files.writeString(root.resolve("results"), "a file where the results directory should be");

        TaskContext ctx = context(StepFixtures.NO_SIMULATION, List.of());
        assertThatThrownBy(() -> step("seed/results/postcard.png").execute(ctx))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo(FailureCode.RESULT_WRITE_FAILED);
                    assertThat(e.reason()).isEqualTo("结果文件生成失败");
                });
        assertThat(ctx.resultUrl()).isNull();
    }

    @Test
    void simulatedFailureWritesNoResult() throws IOException {
        TaskContext ctx = context(StepFixtures.failAt(TaskStage.RESULT_ORGANIZING, "自定义原因"), List.of());

        assertThatThrownBy(() -> step("seed/results/postcard.png").execute(ctx))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo(FailureCode.SIMULATED_FAILURE);
                    assertThat(e.reason()).isEqualTo("自定义原因");
                });
        assertThat(ctx.resultUrl()).isNull();
        assertThat(Files.exists(root.resolve("results"))).isFalse();
    }

    @Test
    void stageIsResultOrganizing() {
        ResultOrganizeStep step = new ResultOrganizeStep(new SimulationGate(ms -> { }), catalog,
                new FileStorageService(new StorageProperties(root)));
        assertThat(step.stage()).isEqualTo(TaskStage.RESULT_ORGANIZING);
        verifyNoInteractions(catalog);
    }

    // ---------- helpers ----------

    private void expectWriteFailed(ResultOrganizeStep step) throws IOException {
        TaskContext ctx = context(StepFixtures.NO_SIMULATION, List.of());
        assertThatThrownBy(() -> step.execute(ctx))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo(FailureCode.RESULT_WRITE_FAILED);
                    assertThat(e.reason()).isEqualTo("结果文件生成失败");
                });
        assertThat(ctx.resultUrl()).isNull();
        assertThat(filesUnder(root.resolve("results"))).isEmpty();
    }

    private ResultOrganizeStep step(String samplePath) {
        when(catalog.getTemplate(TEMPLATE_ID)).thenReturn(StepFixtures.template(1, List.of("image/png"), 1024, samplePath));
        return new ResultOrganizeStep(new SimulationGate(ms -> { }), catalog,
                new FileStorageService(new StorageProperties(root)));
    }

    private static byte[] classpathBytes(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static List<Path> filesUnder(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }
}
