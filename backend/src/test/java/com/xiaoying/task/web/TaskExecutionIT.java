package com.xiaoying.task.web;

import static com.xiaoying.task.web.TaskSubmissionIT.file;
import static com.xiaoying.task.web.TaskSubmissionIT.jpeg;
import static com.xiaoying.task.web.TaskSubmissionIT.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.xiaoying.TestcontainersConfiguration;
import com.xiaoying.auth.security.SessionCookies;
import com.xiaoying.auth.service.SessionService;
import com.xiaoying.task.domain.TaskStage;
import com.xiaoying.task.mapper.TaskMapper;
import com.xiaoying.task.pipeline.TaskPipeline;

import tools.jackson.databind.json.JsonMapper;

/**
 * T-042 完成定义（backlog）：真实线程池 + 真实 MySQL，提交后不发任何查询请求，任务也会由后台线程自行推进；
 * 在 DB 中能观察到 stage 依次经过 ASSET_CHECK → GENERATING → RESULT_ORGANIZING，最终 SUCCEEDED；
 * 任务被外部改为终态（删除账户时废弃）后，pipeline 不再推进、不写结果。
 *
 * <p>步骤目前是 T-042 的占位实现（按快照耗时等待），真实步骤与模拟失败由 T-043 / T-044 覆盖。
 */
@SpringBootTest(properties = {
        "app.simulation.asset-check-ms=300",
        "app.simulation.generating-ms=600",
        "app.simulation.result-organizing-ms=300"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TaskExecutionIT {

    private static final String HASH = "$2a$10$BpxBLDiVUYoM.u0vpiOWEuR1mpe3GVxuFy0hTByNsvFr8zfdU1Ts6";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** jpeg / png / webp，1 张。 */
    private static final String POSTER = "creative-poster";

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

    long user;
    Cookie cookie;

    @BeforeEach
    void setUp() {
        user = insertUser();
        cookie = new Cookie(SessionCookies.NAME, sessions.create(user));
    }

    @Test
    void submittedTaskAdvancesThroughAllStagesInOrderOnWorkerThreadAndSucceeds() throws Exception {
        Set<String> workerThreads = ConcurrentHashMap.newKeySet();
        doAnswer(inv -> {
            workerThreads.add(Thread.currentThread().getName());
            return inv.callRealMethod();
        }).when(pipeline).run(anyLong());

        long start = System.nanoTime();
        long taskId = submit(file("p.jpg", "image/jpeg", jpeg(2048)));
        // 接口不等待执行（总模拟耗时 1.2 s）
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));

        // 只读 DB（不调用任何查询接口），记录观察到的 (status, stage) 变化
        List<String> transitions = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(10)).until(() -> {
            Map<String, Object> row = row(taskId);
            String state = row.get("status") + "/" + row.get("stage");
            if (transitions.isEmpty() || !transitions.getLast().equals(state)) {
                transitions.add(state);
            }
            return "SUCCEEDED".equals(row.get("status"));
        });

        // 状态只前进不后退：观察到的序列恰好是这几步（第一次读取时可能还是 PENDING）
        if (transitions.getFirst().equals("PENDING/null")) {
            transitions.removeFirst();
        }
        assertThat(transitions).containsExactly(
                "PROCESSING/ASSET_CHECK", "PROCESSING/GENERATING", "PROCESSING/RESULT_ORGANIZING",
                "SUCCEEDED/RESULT_ORGANIZING");

        Map<String, Object> row = row(taskId);
        assertThat((String) row.get("result_url")).matches("^/files/seed/results/[a-z-]+\\.png$");
        assertThat(row.get("failed_stage")).isNull();
        assertThat(row.get("failure_code")).isNull();
        assertThat(row.get("failure_reason")).isNull();
        assertThat(row.get("started_at")).isNotNull();
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(workerThreads).isNotEmpty().allSatisfy(name -> assertThat(name).startsWith("task-exec-"));

        mvc.perform(get("/api/tasks/{id}", taskId).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.stage").value("RESULT_ORGANIZING"))
                .andExpect(jsonPath("$.resultUrl").value(row.get("result_url")));
    }

    @Test
    void taskAbandonedDuringGeneratingIsNotAdvancedAndGetsNoResult() throws Exception {
        long taskId = submit(file("p.png", "image/png", png(1024)));

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10))
                .until(() -> "GENERATING".equals(row(taskId).get("stage")));
        // 删除账户时的废弃（F-28）：外部把任务改为终态
        assertThat(taskMapper.abandonActiveByUser(user)).isEqualTo(1);

        // GENERATING 结束后 pipeline 尝试进入下一阶段，条件更新影响 0 行 → 停止
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(taskMapper, atLeastOnce()).markProcessing(taskId, TaskStage.RESULT_ORGANIZING));
        Thread.sleep(500);   // 给 pipeline 留出（不应发生的）后续写入时间

        Map<String, Object> row = row(taskId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_code")).isEqualTo("ACCOUNT_DELETED");
        assertThat(row.get("failed_stage")).isEqualTo("GENERATING");
        assertThat(row.get("stage")).isEqualTo("GENERATING");
        assertThat(row.get("result_url")).isNull();
        verify(taskMapper, never()).markSucceeded(eq(taskId), any());
        verify(taskMapper, never()).markFailed(eq(taskId), any(), any(), any());
    }

    @Test
    void severalTasksAreAllExecutedToTheEnd() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(submit(file("p" + i + ".jpg", "image/jpeg", jpeg(1024 + i))));
        }
        assertThat(ids).doesNotHaveDuplicates();

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).until(() -> ids.stream()
                .map(id -> row(id).get("status")).allMatch(s -> Objects.equals(s, "SUCCEEDED")));
    }

    // ---------- helpers ----------

    private long submit(MockMultipartFile f) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/api/tasks");
        req.file(f);
        String body = mvc.perform(req.param("templateId", POSTER).cookie(cookie))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Long.parseLong(JSON.readTree(body).get("taskId").asString());
    }

    private Map<String, Object> row(long taskId) {
        return jdbc.queryForMap("SELECT status, stage, failed_stage, failure_code, failure_reason, result_url, "
                + "started_at, finished_at FROM tasks WHERE id = ?", taskId);
    }

    private long insertUser() {
        String name = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)",
                name, name + "@example.com", HASH);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, name);
    }
}
