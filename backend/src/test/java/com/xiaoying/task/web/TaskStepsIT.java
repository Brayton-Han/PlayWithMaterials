package com.xiaoying.task.web;

import static com.xiaoying.task.web.TaskSubmissionIT.file;
import static com.xiaoying.task.web.TaskSubmissionIT.jpeg;
import static com.xiaoying.task.web.TaskSubmissionIT.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.xiaoying.TestcontainersConfiguration;
import com.xiaoying.auth.security.SessionCookies;
import com.xiaoying.auth.service.SessionService;
import com.xiaoying.task.domain.TaskStage;
import com.xiaoying.task.pipeline.SimulationGate;
import com.xiaoying.task.pipeline.TaskDispatcher;
import com.xiaoying.task.pipeline.TaskPipeline;

import tools.jackson.databind.json.JsonMapper;

/**
 * T-043 完成定义（backlog）：真实 MySQL + 真实存储目录 + 真实步骤 Bean。
 * 通过接口提交任务（派发器被替换，不进线程池），在测试线程中同步调用 {@link TaskPipeline#run}，
 * 以便在执行前确定地修改素材文件 / 记录 / 模拟快照：
 * <ul>
 *   <li>成功任务的 resultUrl 指向 {@code results/{taskId}/} 下与模板预置结果图内容一致的文件；</li>
 *   <li>手动删掉素材文件 → 在 ASSET_CHECK 失败（ASSET_MISSING），不进入后续阶段、不产生结果文件；</li>
 *   <li>文件被改写 / 素材记录被删 → ASSET_INVALID / ASSET_MISSING；</li>
 *   <li>快照中的 failStage → 在对应阶段 SIMULATED_FAILURE（默认 / 自定义文案）。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        // 专用存储目录：断言 results/{taskId}/ 下只有本任务的文件，不能与其他测试上下文（各自的 MySQL、taskId 都从 1 开始）
        // 或容器环境变量 APP_STORAGE_ROOT 指向的 dev 数据卷共用目录
        "app.storage.root=${java.io.tmpdir}/xiaoying-task-steps-it",
        "app.simulation.asset-check-ms=0",
        "app.simulation.generating-ms=0",
        "app.simulation.result-organizing-ms=0"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TaskStepsIT {

    private static final String HASH = "$2a$10$BpxBLDiVUYoM.u0vpiOWEuR1mpe3GVxuFy0hTByNsvFr8zfdU1Ts6";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String POSTCARD = "demo-postcard";   // 2 张，jpeg / png
    private static final String RESULT_URL = "^/files/results/%d/[0-9a-f-]{36}\\.png$";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @Autowired
    TaskPipeline pipeline;

    @MockitoBean
    TaskDispatcher dispatcher;

    @Value("${app.storage.root}")
    Path storageRoot;

    Cookie cookie;

    @BeforeEach
    void setUp() {
        cookie = new Cookie(SessionCookies.NAME, sessions.create(insertUser()));
    }

    @Test
    void successfulTaskGetsATaskSpecificCopyOfTheTemplateSampleResult() throws Exception {
        long taskId = submit();

        pipeline.run(taskId);

        Map<String, Object> row = row(taskId);
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("stage")).isEqualTo("RESULT_ORGANIZING");
        assertThat(row.get("failure_code")).isNull();
        String resultUrl = (String) row.get("result_url");
        assertThat(resultUrl).matches(RESULT_URL.formatted(taskId));

        Path result = storageRoot.resolve(resultUrl.substring("/files/".length()));
        assertThat(Files.readAllBytes(result)).isEqualTo(classpathBytes("seed/results/postcard.png"));
        assertThat(resultFiles(taskId)).containsExactly(result);   // 没有 .part 残留

        mvc.perform(get("/api/tasks/{id}", taskId).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resultUrl").value(resultUrl));
    }

    @Test
    void resultUrlIsNotPubliclyReadable() throws Exception {
        long taskId = submit();
        pipeline.run(taskId);

        // 结果文件属于用户数据（DQ-02）：匿名一律 401；本人访问由 T-047 的 UserFileController 提供
        mvc.perform(get((String) row(taskId).get("result_url"))).andExpect(status().isUnauthorized());
    }

    @Test
    void deletingAnAssetFileMakesTheTaskFailAtAssetCheck() throws Exception {
        long taskId = submit();
        Files.delete(storageRoot.resolve(assetKeys(taskId).get(1)));

        pipeline.run(taskId);

        assertFailed(taskId, TaskStage.ASSET_CHECK, "ASSET_MISSING", "第 2 张素材文件缺失");
        assertThat(resultFiles(taskId)).isEmpty();
        mvc.perform(get("/api/tasks/{id}", taskId).cookie(cookie))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failedStage").value("ASSET_CHECK"))
                .andExpect(jsonPath("$.failureCode").value("ASSET_MISSING"))
                .andExpect(jsonPath("$.failureReason").value("第 2 张素材文件缺失"))
                .andExpect(jsonPath("$.resultUrl").isEmpty());
    }

    @Test
    void modifiedAssetFileFailsWithAssetInvalid() throws Exception {
        long taskId = submit();
        Path first = storageRoot.resolve(assetKeys(taskId).getFirst());
        byte[] text = new byte[(int) Files.size(first)];
        Arrays.fill(text, (byte) 'x');
        Files.write(first, text);   // 大小不变，内容不再是图片

        pipeline.run(taskId);

        assertFailed(taskId, TaskStage.ASSET_CHECK, "ASSET_INVALID", "第 1 张素材文件类型不符合模板要求");
        assertThat(resultFiles(taskId)).isEmpty();
    }

    @Test
    void truncatedAssetFileFailsWithAssetInvalid() throws Exception {
        long taskId = submit();
        Path second = storageRoot.resolve(assetKeys(taskId).get(1));
        Files.write(second, png(64));

        pipeline.run(taskId);

        assertFailed(taskId, TaskStage.ASSET_CHECK, "ASSET_INVALID", "第 2 张素材文件大小与记录不一致");
    }

    @Test
    void missingAssetRecordFailsWithAssetMissing() throws Exception {
        long taskId = submit();
        jdbc.update("DELETE FROM task_assets WHERE task_id = ? AND slot_index = 1", taskId);

        pipeline.run(taskId);

        assertFailed(taskId, TaskStage.ASSET_CHECK, "ASSET_MISSING", "素材数量与模板要求不一致");
    }

    @ParameterizedTest
    @EnumSource(TaskStage.class)
    void failStageInTheSnapshotFailsExactlyThatStageWithTheDefaultReason(TaskStage stage) throws Exception {
        long taskId = submit();
        setSnapshot(taskId, stage, null);

        pipeline.run(taskId);

        assertFailed(taskId, stage, "SIMULATED_FAILURE", SimulationGate.defaultReason(stage));
        assertThat(resultFiles(taskId)).isEmpty();   // 结果整理阶段的模拟失败发生在写文件之前
    }

    @Test
    void customFailReasonFromTheSnapshotIsStored() throws Exception {
        long taskId = submit();
        setSnapshot(taskId, TaskStage.GENERATING, "create watermark failed");

        pipeline.run(taskId);

        assertFailed(taskId, TaskStage.GENERATING, "SIMULATED_FAILURE", "create watermark failed");
    }

    // ---------- helpers ----------

    private void assertFailed(long taskId, TaskStage stage, String code, String reason) {
        Map<String, Object> row = row(taskId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("stage")).isEqualTo(stage.name());          // 失败后不再进入下一阶段
        assertThat(row.get("failed_stage")).isEqualTo(stage.name());
        assertThat(row.get("failure_code")).isEqualTo(code);
        assertThat(row.get("failure_reason")).isEqualTo(reason);
        assertThat(row.get("result_url")).isNull();
        assertThat(row.get("finished_at")).isNotNull();
    }

    private long submit() throws Exception {
        MockMultipartFile a = file("a.jpg", "image/jpeg", jpeg(2048));
        MockMultipartFile b = file("b.png", "image/png", png(1024));
        MockMultipartHttpServletRequestBuilder req = multipart("/api/tasks");
        req.file(a).file(b);
        String body = mvc.perform(req.param("templateId", POSTCARD).cookie(cookie))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Long.parseLong(JSON.readTree(body).get("taskId").asString());
    }

    private void setSnapshot(long taskId, TaskStage failStage, String failReason) {
        String json = JSON.writeValueAsString(Map.of("assetCheckMs", 0, "generatingMs", 0, "resultOrganizingMs", 0));
        jdbc.update("UPDATE tasks SET sim_config = JSON_SET(CAST(? AS JSON), '$.failStage', ?, '$.failReason', ?) "
                + "WHERE id = ?", json, failStage.name(), failReason, taskId);
    }

    private List<String> assetKeys(long taskId) {
        return jdbc.queryForList("SELECT storage_key FROM task_assets WHERE task_id = ? ORDER BY slot_index",
                String.class, taskId);
    }

    private List<Path> resultFiles(long taskId) throws IOException {
        Path dir = storageRoot.resolve("results").resolve(Long.toString(taskId));
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        }
    }

    private Map<String, Object> row(long taskId) {
        return jdbc.queryForMap("SELECT status, stage, failed_stage, failure_code, failure_reason, result_url, "
                + "finished_at FROM tasks WHERE id = ?", taskId);
    }

    private static byte[] classpathBytes(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }

    private long insertUser() {
        String name = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)",
                name, name + "@example.com", HASH);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, name);
    }
}
