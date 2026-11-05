package com.xiaoying.task.web;

import static com.xiaoying.task.web.TaskSubmissionIT.file;
import static com.xiaoying.task.web.TaskSubmissionIT.jpeg;
import static com.xiaoying.task.web.TaskSubmissionIT.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.xiaoying.TestcontainersConfiguration;
import com.xiaoying.auth.security.SessionCookies;
import com.xiaoying.auth.service.SessionService;
import com.xiaoying.task.mapper.TaskMapper;
import com.xiaoying.task.pipeline.TaskPipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-046 后端核心测试清单第 5、6 项（implementation-plan §3），真实线程池 + 真实步骤 + Testcontainers MySQL，
 * 每阶段模拟耗时 5 s：
 * <ul>
 *   <li>第 6 项（R-04、F-05）：提交接口 &lt; 1 s 返回 202；不发任何查询请求，任务由后台线程自行到达 SUCCEEDED，
 *       {@code resultUrl} 与素材 URL 可经 MockMvc 打开；</li>
 *   <li>第 5 项（R-05.2）：任务处于 PROCESSING 时连续 GET 20 次，DB 行（含 {@code updated_at}）不变、
 *       不产生任何状态写入、pipeline 没有额外调用；</li>
 *   <li>F-07 验收第 1 条：执行期间多次查询，状态 / 阶段只前进不后退；终态后多次查询结果恒定。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.simulation.asset-check-ms=5000",
        "app.simulation.generating-ms=5000",
        "app.simulation.result-organizing-ms=5000",
        "app.storage.root=${java.io.tmpdir}/xiaoying-task-flow-it"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TaskFlowIT {

    private static final String HASH = "$2a$10$BpxBLDiVUYoM.u0vpiOWEuR1mpe3GVxuFy0hTByNsvFr8zfdU1Ts6";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** jpeg / png / webp，1 张。 */
    private static final String POSTER = "creative-poster";
    /** 三阶段共 15 s，留足余量。 */
    private static final Duration TERMINAL_TIMEOUT = Duration.ofSeconds(40);
    /** 执行顺序：(status, stage) 在此列表中的下标只能递增。 */
    private static final List<String> ORDER = List.of(
            "PENDING/null",
            "PROCESSING/ASSET_CHECK", "PROCESSING/GENERATING", "PROCESSING/RESULT_ORGANIZING",
            "SUCCEEDED/RESULT_ORGANIZING");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @MockitoSpyBean
    TaskMapper taskMapper;

    @MockitoSpyBean
    TaskPipeline pipeline;

    Cookie cookie;

    @BeforeEach
    void setUp() {
        cookie = new Cookie(SessionCookies.NAME, sessions.create(insertUser()));
    }

    /** 第 6 项：长耗时配置下快速受理，不查询也能自行完成，结果可访问。 */
    @Test
    void submitReturnsWithinOneSecondAndTaskSucceedsOnItsOwnWithAccessibleResult() throws Exception {
        long start = System.nanoTime();
        JsonNode accepted = submit(file("p.jpg", "image/jpeg", jpeg(4096)));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        assertThat(accepted.get("status").asString()).isEqualTo("PENDING");
        long taskId = Long.parseLong(accepted.get("taskId").asString());

        // 受理时任务的模拟配置快照就是本类的 5 s 配置
        JsonNode sim = JSON.readTree(
                jdbc.queryForObject("SELECT sim_config FROM tasks WHERE id = ?", String.class, taskId));
        assertThat(sim.get("assetCheckMs").asInt()).isEqualTo(5000);
        assertThat(sim.get("generatingMs").asInt()).isEqualTo(5000);
        assertThat(sim.get("resultOrganizingMs").asInt()).isEqualTo(5000);

        // 不调用任何查询接口，只读 DB 等待终态
        await().atMost(TERMINAL_TIMEOUT).pollInterval(Duration.ofMillis(200))
                .until(() -> "SUCCEEDED".equals(row(taskId).get("status")));
        Map<String, Object> row = row(taskId);
        assertThat(row.get("stage")).isEqualTo("RESULT_ORGANIZING");
        assertThat(row.get("failure_code")).isNull();
        String resultUrl = (String) row.get("result_url");
        assertThat(resultUrl).matches("^/files/results/" + taskId + "/[0-9a-f-]{36}\\.png$");
        verify(pipeline, times(1)).run(taskId);

        // 终态后经查询接口得到结果，结果图与素材都能通过 MockMvc 打开
        String body = mvc.perform(get("/api/tasks/{id}", taskId).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resultUrl").value(resultUrl))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        mvc.perform(get(resultUrl).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
        String assetUrl = JSON.readTree(body).get("inputAssets").get(0).get("url").asString();
        mvc.perform(get(assetUrl).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"));
    }

    /** 第 5 项 + F-07 验收第 1 条：查询只读、不推进；执行期间状态只前进；终态后结果恒定。 */
    @Test
    void queriesDuringProcessingNeverChangeTheTaskAndOnlyObserveForwardProgress() throws Exception {
        long taskId = Long.parseLong(submit(file("p.png", "image/png", png(2048))).get("taskId").asString());

        // 进入 GENERATING（本阶段持续 5 s，期间 pipeline 只在 sleep，不写 DB）
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(20))
                .until(() -> "GENERATING".equals(row(taskId).get("stage")));
        Map<String, Object> before = fullRow(taskId);
        assertThat(before.get("status")).isEqualTo("PROCESSING");
        clearInvocations(taskMapper, pipeline);

        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/api/tasks/{id}", taskId).cookie(cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PROCESSING"))
                    .andExpect(jsonPath("$.stage").value("GENERATING"))
                    .andExpect(jsonPath("$.resultUrl").value(nullValue()));
            mvc.perform(get("/api/tasks").param("templateId", POSTER).cookie(cookie))
                    .andExpect(status().isOk());
        }

        // DB 行完全不变（含 updated_at），没有任何状态写入，pipeline 没有被再次调用
        assertThat(fullRow(taskId)).isEqualTo(before);
        verify(taskMapper, never()).markProcessing(anyLong(), any());
        verify(taskMapper, never()).markSucceeded(anyLong(), any());
        verify(taskMapper, never()).markFailed(anyLong(), any(), any(), any());
        verify(pipeline, never()).run(anyLong());

        // 继续经查询接口观察到终态：状态 / 阶段的顺序只前进不后退
        List<String> observed = new ArrayList<>();
        await().atMost(TERMINAL_TIMEOUT).pollInterval(Duration.ofMillis(100)).until(() -> {
            JsonNode t = query(taskId);
            String state = t.get("status").asString() + "/" + (t.get("stage").isNull() ? "null" : t.get("stage").asString());
            observed.add(state);
            return t.get("status").asString().equals("SUCCEEDED");
        });
        assertThat(observed).allSatisfy(s -> assertThat(ORDER).contains(s));
        for (int i = 1; i < observed.size(); i++) {
            assertThat(ORDER.indexOf(observed.get(i))).isGreaterThanOrEqualTo(ORDER.indexOf(observed.get(i - 1)));
        }
        assertThat(observed.getFirst()).isIn("PROCESSING/GENERATING", "PROCESSING/RESULT_ORGANIZING");

        // 终态后多次查询结果恒定，DB 也不再变化
        JsonNode terminal = query(taskId);
        Map<String, Object> terminalRow = fullRow(taskId);
        for (int i = 0; i < 5; i++) {
            assertThat(query(taskId)).isEqualTo(terminal);
        }
        assertThat(fullRow(taskId)).isEqualTo(terminalRow);
    }

    // ---------- helpers ----------

    private JsonNode submit(MockMultipartFile f) throws Exception {
        String body = mvc.perform(multipart("/api/tasks").file(f).param("templateId", POSTER).cookie(cookie))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    private JsonNode query(long taskId) throws Exception {
        String body = mvc.perform(get("/api/tasks/{id}", taskId).cookie(cookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    private Map<String, Object> row(long taskId) {
        return jdbc.queryForMap("SELECT status, stage, failure_code, result_url FROM tasks WHERE id = ?", taskId);
    }

    private Map<String, Object> fullRow(long taskId) {
        return jdbc.queryForMap("SELECT * FROM tasks WHERE id = ?", taskId);
    }

    private long insertUser() {
        String name = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)",
                name, name + "@example.com", HASH);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, name);
    }
}
