package com.xiaoying.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.xiaoying.TestcontainersConfiguration;
import com.xiaoying.auth.security.SessionCookies;
import com.xiaoying.auth.service.SessionService;
import com.xiaoying.task.domain.TaskStage;
import com.xiaoying.task.mapper.TaskMapper;

import tools.jackson.databind.json.JsonMapper;

/**
 * T-046 后端核心测试清单第 8 项（implementation-plan §3）/ F-28 规则 4、5、DQ-02，经真实的 {@code DELETE /api/account}：
 * 真实线程池 + 真实步骤 + Testcontainers MySQL，每阶段 2 s。
 * 先让一个任务成功（得到结果图链接），再提交第二个任务并在其执行中删除账户：
 * 第二个任务最终 {@code FAILED + ACCOUNT_DELETED}，{@code stage} 没有前进、{@code result_url} 为 NULL；
 * 删除前记下的素材和结果链接对原 Cookie 返回 401、对其他用户返回 404；文件仍在磁盘上。
 */
@SpringBootTest(properties = {
        "app.simulation.asset-check-ms=2000",
        "app.simulation.generating-ms=2000",
        "app.simulation.result-organizing-ms=2000",
        "app.storage.root=${java.io.tmpdir}/xiaoying-account-deletion-it"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccountDeletionFlowIT {

    private static final String HASH = "$2a$10$BpxBLDiVUYoM.u0vpiOWEuR1mpe3GVxuFy0hTByNsvFr8zfdU1Ts6";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String POSTER = "creative-poster";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> STAGES = List.of("ASSET_CHECK", "GENERATING", "RESULT_ORGANIZING");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @MockitoSpyBean
    TaskMapper taskMapper;

    @Value("${app.storage.root}")
    Path storageRoot;

    @Test
    void deletingAccountMidTaskAbandonsItAndInvalidatesFileLinksButKeepsData() throws Exception {
        long userId = insertUser();
        Cookie owner = new Cookie(SessionCookies.NAME, sessions.create(userId));
        Cookie other = new Cookie(SessionCookies.NAME, sessions.create(insertUser()));

        // 1) 一个已成功的任务：得到结果图链接
        long done = submit(owner);
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(100))
                .until(() -> "SUCCEEDED".equals(row(done).get("status")));
        Map<String, Object> doneBefore = fullRow(done);

        // 2) 第二个任务执行中删除账户
        long running = submit(owner);
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(20))
                .until(() -> "PROCESSING".equals(row(running).get("status")));
        mvc.perform(delete("/api/account").cookie(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmText\":\"DELETE\"}"))
                .andExpect(status().isNoContent());
        String stageAtDeletion = (String) row(running).get("stage");
        assertThat(stageAtDeletion).isIn(STAGES);

        // 当前步骤执行完后 pipeline 尝试进入下一阶段 / 写结果，条件更新影响 0 行 → 停止
        if (!"RESULT_ORGANIZING".equals(stageAtDeletion)) {
            TaskStage next = TaskStage.valueOf(STAGES.get(STAGES.indexOf(stageAtDeletion) + 1));
            await().atMost(TIMEOUT).untilAsserted(() -> verify(taskMapper).markProcessing(running, next));
        } else {
            await().atMost(TIMEOUT).untilAsserted(() -> verify(taskMapper).markSucceeded(eq(running), any()));
        }
        Thread.sleep(500);   // 给 pipeline 留出（不应发生的）后续写入时间

        Map<String, Object> abandoned = fullRow(running);
        assertThat(abandoned.get("status")).isEqualTo("FAILED");
        assertThat(abandoned.get("failure_code")).isEqualTo("ACCOUNT_DELETED");
        assertThat(abandoned.get("failure_reason")).isEqualTo("账户已删除，任务已废弃");
        assertThat(abandoned.get("stage")).isEqualTo(stageAtDeletion);
        assertThat(abandoned.get("failed_stage")).isEqualTo(stageAtDeletion);
        assertThat(abandoned.get("result_url")).isNull();
        verify(taskMapper, never()).markFailed(eq(running), any(), any(), any());

        // 已成功的任务不变；任务、素材行都还在；文件仍在磁盘上
        assertThat(fullRow(done)).isEqualTo(doneBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(2);
        List<String> urls = new ArrayList<>(assetUrls(done));
        urls.addAll(assetUrls(running));
        urls.add((String) doneBefore.get("result_url"));
        assertThat(urls).hasSize(3);
        for (String url : urls) {
            assertThat(Files.isRegularFile(storageRoot.resolve(url.substring("/files/".length()))))
                    .as("file on disk: %s", url).isTrue();
            // 原 Cookie：会话已作废 → 401；其他用户：不属于他 → 404（DQ-02）
            mvc.perform(get(URI.create(url)).cookie(owner))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            mvc.perform(get(URI.create(url)).cookie(other))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    // ---------- helpers ----------

    private long submit(Cookie cookie) throws Exception {
        String body = mvc.perform(multipart("/api/tasks").file(new MockMultipartFile("files", "p.jpg", "image/jpeg", jpeg()))
                        .param("templateId", POSTER).cookie(cookie))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Long.parseLong(JSON.readTree(body).get("taskId").asString());
    }

    /** 只有文件头是真实的：服务端只按魔数判定类型。 */
    private static byte[] jpeg() {
        byte[] bytes = new byte[2048];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        return bytes;
    }

    private Map<String, Object> row(long taskId) {
        return jdbc.queryForMap("SELECT status, stage FROM tasks WHERE id = ?", taskId);
    }

    private Map<String, Object> fullRow(long taskId) {
        return jdbc.queryForMap("SELECT * FROM tasks WHERE id = ?", taskId);
    }

    private List<String> assetUrls(long taskId) {
        return jdbc.queryForList("SELECT CONCAT('/files/', storage_key) FROM task_assets WHERE task_id = ? "
                + "ORDER BY slot_index", String.class, taskId);
    }

    private long insertUser() {
        String name = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)",
                name, name + "@example.com", HASH);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, name);
    }
}
