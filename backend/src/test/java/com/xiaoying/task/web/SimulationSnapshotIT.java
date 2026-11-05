package com.xiaoying.task.web;

import static com.xiaoying.task.web.TaskSubmissionIT.file;
import static com.xiaoying.task.web.TaskSubmissionIT.jpeg;
import static com.xiaoying.task.web.TaskSubmissionIT.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.xiaoying.TestcontainersConfiguration;
import com.xiaoying.auth.security.SessionCookies;
import com.xiaoying.auth.service.SessionService;
import com.xiaoying.simulation.SimulationConfig;
import com.xiaoying.simulation.SimulationSettings;
import com.xiaoying.task.pipeline.TaskDispatcher;
import com.xiaoying.task.pipeline.TaskPipeline;

import tools.jackson.databind.json.JsonMapper;

/**
 * T-044 / OQ-10 临时默认（全局配置 + 创建时快照）：经 {@code PUT /api/dev/simulation} 修改配置后，
 * <ul>
 *   <li>新提交的任务快照为新配置，连续提交的任务都稳定在指定阶段失败（完成定义第 1 条）；</li>
 *   <li>修改前创建的任务仍按旧快照执行（即使执行发生在修改之后）。</li>
 * </ul>
 * 派发器被替换，在测试线程中同步执行 pipeline，以确定"修改配置"与"执行"的先后。
 */
@SpringBootTest(properties = {
        "app.simulation.dev-api-enabled=true",
        // 专用存储目录，不与其他测试上下文的 results/{taskId}/ 混在一起（见 TaskStepsIT）
        "app.storage.root=${java.io.tmpdir}/xiaoying-simulation-snapshot-it"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SimulationSnapshotIT {

    private static final String HASH = "$2a$10$BpxBLDiVUYoM.u0vpiOWEuR1mpe3GVxuFy0hTByNsvFr8zfdU1Ts6";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SimulationConfig DEFAULTS = new SimulationConfig(1500, 3000, 1500, null, null);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @Autowired
    SimulationSettings settings;

    @Autowired
    TaskPipeline pipeline;

    @MockitoBean
    TaskDispatcher dispatcher;

    Cookie cookie;

    @BeforeEach
    void setUp() {
        cookie = new Cookie(SessionCookies.NAME, sessions.create(insertUser()));
    }

    @AfterEach
    void restoreDefaults() {
        settings.update(DEFAULTS);
    }

    @Test
    void afterSettingFailStageGeneratingConsecutiveTasksAllFailAtGenerating() throws Exception {
        putConfig("{\"assetCheckMs\":0,\"generatingMs\":0,\"resultOrganizingMs\":0,"
                + "\"failStage\":\"GENERATING\",\"failReason\":null}");

        for (int i = 0; i < 3; i++) {
            long taskId = submit();
            assertThat(snapshot(taskId)).isEqualTo(Map.of(
                    "assetCheckMs", 0, "generatingMs", 0, "resultOrganizingMs", 0));   // null 字段见下
            assertThat(snapshotRaw(taskId)).contains("\"failStage\":\"GENERATING\"").contains("\"failReason\":null");

            pipeline.run(taskId);

            Map<String, Object> row = row(taskId);
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("stage")).isEqualTo("GENERATING");
            assertThat(row.get("failed_stage")).isEqualTo("GENERATING");
            assertThat(row.get("failure_code")).isEqualTo("SIMULATED_FAILURE");
            assertThat(row.get("failure_reason")).isEqualTo("模拟失败：生成阶段出错");
            assertThat(row.get("result_url")).isNull();

            mvc.perform(get("/api/tasks/{id}", taskId).cookie(cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.failedStage").value("GENERATING"))
                    .andExpect(jsonPath("$.failureCode").value("SIMULATED_FAILURE"));
        }
    }

    @Test
    void tasksCreatedBeforeAChangeKeepTheirOldSnapshot() throws Exception {
        putConfig("{\"assetCheckMs\":0,\"generatingMs\":0,\"resultOrganizingMs\":0,"
                + "\"failStage\":null,\"failReason\":null}");
        long before = submit();

        putConfig("{\"assetCheckMs\":0,\"generatingMs\":0,\"resultOrganizingMs\":0,"
                + "\"failStage\":\"ASSET_CHECK\",\"failReason\":\"演示用失败\"}");
        long after = submit();

        // 两个任务都在配置修改之后才执行
        pipeline.run(before);
        pipeline.run(after);

        assertThat(row(before).get("status")).isEqualTo("SUCCEEDED");
        assertThat(row(before).get("result_url")).isNotNull();
        assertThat(snapshotRaw(before)).contains("\"failStage\":null");

        Map<String, Object> failed = row(after);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("failed_stage")).isEqualTo("ASSET_CHECK");
        assertThat(failed.get("failure_code")).isEqualTo("SIMULATED_FAILURE");
        assertThat(failed.get("failure_reason")).isEqualTo("演示用失败");
    }

    @Test
    void changingDurationsIsReflectedInTheSnapshotOfNewTasksOnly() throws Exception {
        long before = submit();
        putConfig("{\"assetCheckMs\":10,\"generatingMs\":20,\"resultOrganizingMs\":30,"
                + "\"failStage\":null,\"failReason\":null}");
        long after = submit();

        assertThat(snapshot(before)).isEqualTo(Map.of(
                "assetCheckMs", 1500, "generatingMs", 3000, "resultOrganizingMs", 1500));
        assertThat(snapshot(after)).isEqualTo(Map.of(
                "assetCheckMs", 10, "generatingMs", 20, "resultOrganizingMs", 30));
    }

    // ---------- helpers ----------

    private void putConfig(String json) throws Exception {
        mvc.perform(put("/api/dev/simulation").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    private long submit() throws Exception {
        String body = mvc.perform(multipart("/api/tasks")
                        .file(file("a.jpg", "image/jpeg", jpeg(2048)))
                        .file(file("b.png", "image/png", png(1024)))
                        .param("templateId", "demo-postcard")
                        .cookie(cookie))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Long.parseLong(JSON.readTree(body).get("taskId").asString());
    }

    private String snapshotRaw(long taskId) {
        return JSON.writeValueAsString(JSON.readTree(
                jdbc.queryForObject("SELECT sim_config FROM tasks WHERE id = ?", String.class, taskId)));
    }

    /** 快照中的三个耗时字段（failStage / failReason 由 {@link #snapshotRaw} 断言）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshot(long taskId) {
        Map<String, Object> all = JSON.readValue(
                jdbc.queryForObject("SELECT sim_config FROM tasks WHERE id = ?", String.class, taskId), Map.class);
        return Map.of("assetCheckMs", all.get("assetCheckMs"), "generatingMs", all.get("generatingMs"),
                "resultOrganizingMs", all.get("resultOrganizingMs"));
    }

    private Map<String, Object> row(long taskId) {
        return jdbc.queryForMap("SELECT * FROM tasks WHERE id = ?", taskId);
    }

    private long insertUser() {
        String name = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)",
                name, name + "@example.com", HASH);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, name);
    }
}
