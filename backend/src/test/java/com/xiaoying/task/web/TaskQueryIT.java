package com.xiaoying.task.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.xiaoying.TestcontainersConfiguration;
import com.xiaoying.auth.security.SessionCookies;
import com.xiaoying.auth.service.SessionService;
import com.xiaoying.task.domain.FailureCode;
import com.xiaoying.task.domain.Task;
import com.xiaoying.task.domain.TaskAsset;
import com.xiaoying.task.domain.TaskStage;
import com.xiaoying.task.mapper.TaskAssetMapper;
import com.xiaoying.task.mapper.TaskMapper;

/**
 * T-045 完成定义（backlog）与 implementation-plan §3 中 TaskQueryIT 的条目：
 * 按模板过滤 / 全部、createdAt 倒序、用户隔离（他人任务 404、列表不含他人任务）、非数字 taskId 400、
 * inputAssets 按 slotIndex 升序、未登录 401、查询不改变任务状态。
 * 每个用例使用新建的随机用户，数据互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TaskQueryIT {

    private static final String HASH = "$2a$10$BpxBLDiVUYoM.u0vpiOWEuR1mpe3GVxuFy0hTByNsvFr8zfdU1Ts6";
    private static final String SIM = """
            {"assetCheckMs":1500,"generatingMs":3000,"resultOrganizingMs":1500,"failStage":null,"failReason":null}""";
    private static final String ISO_MILLIS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";
    private static final String X = "demo-postcard";
    private static final String Y = "creative-poster";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TaskMapper tasks;

    @Autowired
    TaskAssetMapper assets;

    @Autowired
    SessionService sessions;

    long alice;
    long bob;
    Cookie aliceCookie;
    Cookie bobCookie;

    @BeforeEach
    void users() {
        alice = insertUser();
        bob = insertUser();
        aliceCookie = new Cookie(SessionCookies.NAME, sessions.create(alice));
        bobCookie = new Cookie(SessionCookies.NAME, sessions.create(bob));
    }

    // ---------- 列表 ----------

    @Test
    void listFiltersByTemplateAndReturnsAllNewestFirstWithoutTemplateId() throws Exception {
        long x1 = newTask(alice, X, "2026-11-01 10:00:00.000");
        long y1 = newTask(alice, Y, "2026-11-02 10:00:00.000");
        long x2 = newTask(alice, X, "2026-11-03 10:00:00.000");

        list(aliceCookie, X)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].taskId").value(contains(id(x2), id(x1))))
                .andExpect(jsonPath("$.items[*].templateId").value(contains(X, X)));

        list(aliceCookie, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].taskId").value(contains(id(x2), id(y1), id(x1))))
                .andExpect(jsonPath("$.items[*].templateId").value(contains(X, Y, X)))
                .andExpect(jsonPath("$.items[0].templateTitle").value("旅行明信片"))
                .andExpect(jsonPath("$.items[0].templateCoverUrl").value("/files/seed/covers/postcard.jpg"))
                .andExpect(jsonPath("$.items[1].templateTitle").isNotEmpty())
                .andExpect(jsonPath("$.items[1].templateCoverUrl").value(matchesPattern("^/files/seed/.+")))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-03T10:00:00.000Z"));
    }

    @Test
    void listBreaksCreatedAtTiesByTaskIdDesc() throws Exception {
        long a = newTask(alice, X, "2026-11-01 10:00:00.000");
        long b = newTask(alice, X, "2026-11-01 10:00:00.000");

        list(aliceCookie, null).andExpect(jsonPath("$.items[*].taskId").value(contains(id(b), id(a))));
    }

    @Test
    void otherUsersNeverSeeTasksInTheirLists() throws Exception {
        newTask(alice, X, "2026-11-01 10:00:00.000");
        newTask(alice, Y, "2026-11-02 10:00:00.000");
        long bobs = newTask(bob, X, "2026-11-03 10:00:00.000");

        list(bobCookie, null).andExpect(jsonPath("$.items[*].taskId").value(contains(id(bobs))));
        list(bobCookie, X).andExpect(jsonPath("$.items[*].taskId").value(contains(id(bobs))));
        list(bobCookie, Y).andExpect(jsonPath("$.items").value(empty()));
    }

    @Test
    void emptyHistoryAndUnknownTemplateReturnEmptyItems() throws Exception {
        list(aliceCookie, null).andExpect(status().isOk()).andExpect(jsonPath("$.items").value(empty()));

        newTask(alice, X, "2026-11-01 10:00:00.000");
        list(aliceCookie, "no-such-template").andExpect(status().isOk()).andExpect(jsonPath("$.items").value(empty()));
    }

    @Test
    void blankTemplateIdMeansAllTemplates() throws Exception {
        newTask(alice, X, "2026-11-01 10:00:00.000");
        newTask(alice, Y, "2026-11-02 10:00:00.000");

        list(aliceCookie, "").andExpect(status().isOk()).andExpect(jsonPath("$.items").value(hasSize(2)));
    }

    @Test
    void tooLongTemplateIdIs400() throws Exception {
        list(aliceCookie, "x".repeat(65))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("templateId"))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("LENGTH"));
    }

    @Test
    void listItemsCarryInputAssetsOfTheirOwnTask() throws Exception {
        long first = newTask(alice, X, "2026-11-01 10:00:00.000");
        long second = newTask(alice, X, "2026-11-02 10:00:00.000");
        String k1 = addAsset(first, 0);
        String k2 = addAsset(second, 0);
        String k3 = addAsset(second, 1);

        list(aliceCookie, X)
                .andExpect(jsonPath("$.items[0].inputAssets[*].url").value(contains("/files/" + k2, "/files/" + k3)))
                .andExpect(jsonPath("$.items[1].inputAssets[*].url").value(contains("/files/" + k1)));
    }

    // ---------- 单任务 ----------

    @Test
    void getReturnsPendingTaskWithAllContractFields() throws Exception {
        long id = newTask(alice, X, null);

        getTask(aliceCookie, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(id(id)))
                .andExpect(jsonPath("$.templateId").value(X))
                .andExpect(jsonPath("$.templateTitle").value("旅行明信片"))
                .andExpect(jsonPath("$.templateCoverUrl").value("/files/seed/covers/postcard.jpg"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                // 契约中可空字段也是必返的：必须以 null 出现，而不是缺省
                .andExpect(jsonPath("$.stage").value(nullValue()))
                .andExpect(jsonPath("$.failedStage").value(nullValue()))
                .andExpect(jsonPath("$.failureCode").value(nullValue()))
                .andExpect(jsonPath("$.failureReason").value(nullValue()))
                .andExpect(jsonPath("$.resultUrl").value(nullValue()))
                .andExpect(jsonPath("$.startedAt").value(nullValue()))
                .andExpect(jsonPath("$.finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.inputAssets").value(empty()))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern(ISO_MILLIS)))
                .andExpect(jsonPath("$.updatedAt").value(matchesPattern(ISO_MILLIS)))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.simConfig").doesNotExist());
    }

    @Test
    void getReturnsInputAssetsSortedBySlotIndex() throws Exception {
        long id = newTask(alice, X, null);
        String k2 = addAsset(id, 2);
        String k0 = addAsset(id, 0);
        String k1 = addAsset(id, 1);

        getTask(aliceCookie, id)
                .andExpect(jsonPath("$.inputAssets[*].slotIndex").value(contains(0, 1, 2)))
                .andExpect(jsonPath("$.inputAssets[*].url").value(contains("/files/" + k0, "/files/" + k1, "/files/" + k2)))
                .andExpect(jsonPath("$.inputAssets[0].storageKey").doesNotExist());
    }

    @Test
    void getReturnsProcessingStage() throws Exception {
        long id = newTask(alice, X, null);
        tasks.markProcessing(id, TaskStage.ASSET_CHECK);
        tasks.markProcessing(id, TaskStage.GENERATING);

        getTask(aliceCookie, id)
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.stage").value("GENERATING"))
                .andExpect(jsonPath("$.startedAt").value(matchesPattern(ISO_MILLIS)))
                .andExpect(jsonPath("$.finishedAt").value(nullValue()));
    }

    @Test
    void getReturnsSucceededResult() throws Exception {
        long id = newTask(alice, X, null);
        for (TaskStage stage : TaskStage.values()) {
            tasks.markProcessing(id, stage);
        }
        String resultUrl = "/files/results/" + id + "/" + UUID.randomUUID() + ".png";
        assertThat(tasks.markSucceeded(id, resultUrl)).isEqualTo(1);

        getTask(aliceCookie, id)
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.stage").value("RESULT_ORGANIZING"))
                .andExpect(jsonPath("$.resultUrl").value(resultUrl))
                .andExpect(jsonPath("$.failureReason").value(nullValue()))
                .andExpect(jsonPath("$.finishedAt").value(matchesPattern(ISO_MILLIS)));
    }

    @Test
    void getReturnsFailedStageAndReason() throws Exception {
        long id = newTask(alice, X, null);
        tasks.markProcessing(id, TaskStage.ASSET_CHECK);
        tasks.markProcessing(id, TaskStage.GENERATING);
        assertThat(tasks.markFailed(id, TaskStage.GENERATING, FailureCode.SIMULATED_FAILURE, "模拟失败：生成阶段出错"))
                .isEqualTo(1);

        getTask(aliceCookie, id)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failedStage").value("GENERATING"))
                .andExpect(jsonPath("$.failureCode").value("SIMULATED_FAILURE"))
                .andExpect(jsonPath("$.failureReason").value("模拟失败：生成阶段出错"))
                .andExpect(jsonPath("$.resultUrl").value(nullValue()))
                .andExpect(jsonPath("$.finishedAt").value(matchesPattern(ISO_MILLIS)));
    }

    @Test
    void otherUsersTaskIs404LikeAMissingOne() throws Exception {
        long id = newTask(alice, X, null);

        getTask(bobCookie, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("任务不存在"));
        mvc.perform(get("/api/tasks/{id}", id + 1_000_000).cookie(aliceCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void nonNumericTaskIdIs400() throws Exception {
        for (String bad : List.of("abc", "-1", "1.5", "12a", "1".repeat(20))) {
            mvc.perform(get("/api/tasks/{id}", bad).cookie(aliceCookie))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details.fieldErrors[0].field").value("taskId"))
                    .andExpect(jsonPath("$.details.fieldErrors[0].code").value("PATTERN"));
        }
    }

    @Test
    void wellFormedIdBeyondLongRangeIs404() throws Exception {
        mvc.perform(get("/api/tasks/{id}", "9999999999999999999").cookie(aliceCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    // ---------- 认证 ----------

    @Test
    void anonymousOrInvalidSessionIs401() throws Exception {
        long id = newTask(alice, X, null);
        Cookie bogus = new Cookie(SessionCookies.NAME, "not-a-real-session-token-0000000000000000000");

        for (Cookie cookie : new Cookie[] {null, bogus}) {
            expect401(cookie == null ? mvc.perform(get("/api/tasks")) : mvc.perform(get("/api/tasks").cookie(cookie)));
            expect401(cookie == null
                    ? mvc.perform(get("/api/tasks/{id}", id))
                    : mvc.perform(get("/api/tasks/{id}", id).cookie(cookie)));
        }
    }

    @Test
    void deletedAccountSessionIs401() throws Exception {
        newTask(alice, X, null);
        jdbc.update("UPDATE users SET deleted_at = NOW(3) WHERE id = ?", alice);

        expect401(mvc.perform(get("/api/tasks").cookie(aliceCookie)));
    }

    // ---------- 只读 ----------

    @Test
    void queriesNeverChangeTaskRows() throws Exception {
        long pending = newTask(alice, X, null);
        long processing = newTask(alice, Y, null);
        tasks.markProcessing(processing, TaskStage.ASSET_CHECK);
        Map<String, Object> pendingBefore = row(pending);
        Map<String, Object> processingBefore = row(processing);

        for (int i = 0; i < 10; i++) {
            getTask(aliceCookie, pending).andExpect(jsonPath("$.status").value("PENDING"));
            getTask(aliceCookie, processing).andExpect(jsonPath("$.stage").value("ASSET_CHECK"));
            list(aliceCookie, null).andExpect(status().isOk());
            list(aliceCookie, X).andExpect(status().isOk());
        }

        assertThat(row(pending)).isEqualTo(pendingBefore);
        assertThat(row(processing)).isEqualTo(processingBefore);
    }

    // ---------- helpers ----------

    private ResultActions list(Cookie cookie, String templateId) throws Exception {
        var request = get("/api/tasks").cookie(cookie);
        if (templateId != null) {
            request = request.queryParam("templateId", templateId);
        }
        return mvc.perform(request);
    }

    private ResultActions getTask(Cookie cookie, long id) throws Exception {
        return mvc.perform(get("/api/tasks/{id}", id).cookie(cookie));
    }

    private static void expect401(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    private static String id(long id) {
        return String.valueOf(id);
    }

    /** {@code createdAt} 为 null 时保留数据库默认值。 */
    private long newTask(long userId, String templateId, String createdAt) {
        Task task = new Task(userId, templateId, SIM);
        tasks.insert(task);
        if (createdAt != null) {
            jdbc.update("UPDATE tasks SET created_at = ? WHERE id = ?", createdAt, task.getId());
        }
        return task.getId();
    }

    private String addAsset(long taskId, int slotIndex) {
        String key = "uploads/2026/11/" + UUID.randomUUID() + ".jpg";
        assets.insert(new TaskAsset(taskId, slotIndex, key, "photo-" + slotIndex + ".jpg", "image/jpeg", 1024));
        return key;
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
