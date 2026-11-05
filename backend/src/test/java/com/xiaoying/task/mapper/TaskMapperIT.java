package com.xiaoying.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.xiaoying.TestcontainersConfiguration;
import com.xiaoying.task.domain.FailureCode;
import com.xiaoying.task.domain.Task;
import com.xiaoying.task.domain.TaskAsset;
import com.xiaoying.task.domain.TaskStage;
import com.xiaoying.task.domain.TaskStatus;

/**
 * T-040 完成定义（backlog）与 implementation-plan §3 中 TaskMapperIT 的条目：
 * 终态不可变、markSucceeded 的阶段前置条件、按用户隔离、abandonActiveByUser 的影响范围。
 * 每个测试方法在事务中执行并回滚。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class TaskMapperIT {

    private static final String HASH = "$2a$10$BpxBLDiVUYoM.u0vpiOWEuR1mpe3GVxuFy0hTByNsvFr8zfdU1Ts6";
    private static final String SIM = """
            {"assetCheckMs":1500,"generatingMs":3000,"resultOrganizingMs":1500,"failStage":null,"failReason":null}""";

    @Autowired
    TaskMapper tasks;

    @Autowired
    TaskAssetMapper assets;

    @Autowired
    JdbcTemplate jdbc;

    long alice;
    long bob;

    @BeforeEach
    void users() {
        alice = insertUser();
        bob = insertUser();
    }

    // ---------- insert / 读取 ----------

    @Test
    void insertCreatesPendingTaskAndFindReturnsItWithTemplateInfo() {
        Task task = newTask(alice, "demo-postcard");

        Task found = tasks.findByIdAndUser(task.getId(), alice);
        assertThat(found).isNotNull();
        assertThat(found.getUserId()).isEqualTo(alice);
        assertThat(found.getTemplateId()).isEqualTo("demo-postcard");
        assertThat(found.getTemplateTitle()).isEqualTo("旅行明信片");
        assertThat(found.getTemplateCoverUrl()).isEqualTo("/files/seed/covers/postcard.jpg");
        assertThat(found.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(found.getStage()).isNull();
        assertThat(found.getFailedStage()).isNull();
        assertThat(found.getFailureCode()).isNull();
        assertThat(found.getFailureReason()).isNull();
        assertThat(found.getResultUrl()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getStartedAt()).isNull();
        assertThat(found.getFinishedAt()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT sim_config = CAST(? AS JSON) FROM tasks WHERE id = ?", Boolean.class, SIM, task.getId()))
                .isTrue();
        assertThat(found.getSimConfig()).contains("\"generatingMs\": 3000");
    }

    @Test
    void findByIdAndUserHidesOtherUsersAndMissingTasks() {
        Task task = newTask(alice, "demo-postcard");

        assertThat(tasks.findByIdAndUser(task.getId(), bob)).isNull();
        assertThat(tasks.findByIdAndUser(task.getId() + 100_000, alice)).isNull();
    }

    @Test
    void listByUserFiltersByTemplateAndOrdersNewestFirst() {
        Task x1 = newTask(alice, "demo-postcard");
        Task y1 = newTask(alice, "creative-poster");
        Task x2 = newTask(alice, "demo-postcard");
        Task bobsX = newTask(bob, "demo-postcard");
        setCreatedAt(x1, "2026-11-01 10:00:00.000");
        setCreatedAt(y1, "2026-11-02 10:00:00.000");
        setCreatedAt(x2, "2026-11-03 10:00:00.000");
        setCreatedAt(bobsX, "2026-11-04 10:00:00.000");

        assertThat(ids(tasks.listByUser(alice, "demo-postcard"))).containsExactly(x2.getId(), x1.getId());
        assertThat(ids(tasks.listByUser(alice, null))).containsExactly(x2.getId(), y1.getId(), x1.getId());
        assertThat(ids(tasks.listByUser(bob, null))).containsExactly(bobsX.getId());
        assertThat(tasks.listByUser(bob, "creative-poster")).isEmpty();
        assertThat(tasks.listByUser(alice, "no-such-template")).isEmpty();
        assertThat(tasks.listByUser(alice, null)).allSatisfy(t -> assertThat(t.getTemplateTitle()).isNotBlank());
    }

    @Test
    void listByUserBreaksCreatedAtTiesByIdDesc() {
        Task a = newTask(alice, "demo-postcard");
        Task b = newTask(alice, "demo-postcard");
        setCreatedAt(a, "2026-11-01 10:00:00.000");
        setCreatedAt(b, "2026-11-01 10:00:00.000");

        assertThat(ids(tasks.listByUser(alice, null))).containsExactly(b.getId(), a.getId());
    }

    @Test
    void listByIdsAndUserReturnsOnlyOwnTasks() {
        Task mine = newTask(alice, "demo-postcard");
        Task theirs = newTask(bob, "demo-postcard");

        assertThat(ids(tasks.listByIdsAndUser(alice, List.of(mine.getId(), theirs.getId()))))
                .containsExactly(mine.getId());
        assertThat(tasks.listByIdsAndUser(bob, List.of(mine.getId()))).isEmpty();
        assertThat(tasks.listByIdsAndUser(alice, List.of())).isEmpty();
    }

    // ---------- 合法的状态推进 ----------

    @Test
    void fullSuccessPathRecordsStageTimesAndResult() {
        Task task = newTask(alice, "demo-postcard");
        long id = task.getId();

        assertThat(tasks.markProcessing(id, TaskStage.ASSET_CHECK)).isEqualTo(1);
        Task checking = tasks.findByIdAndUser(id, alice);
        assertThat(checking.getStatus()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(checking.getStage()).isEqualTo(TaskStage.ASSET_CHECK);
        assertThat(checking.getStartedAt()).isNotNull();

        jdbc.update("UPDATE tasks SET started_at = '2026-11-01 00:00:00.000' WHERE id = ?", id);
        assertThat(tasks.markProcessing(id, TaskStage.GENERATING)).isEqualTo(1);
        assertThat(tasks.markProcessing(id, TaskStage.RESULT_ORGANIZING)).isEqualTo(1);
        Task organizing = tasks.findByIdAndUser(id, alice);
        assertThat(organizing.getStage()).isEqualTo(TaskStage.RESULT_ORGANIZING);
        // started_at 只在第一次 markProcessing 时写入
        assertThat(organizing.getStartedAt()).isEqualTo("2026-11-01T00:00:00Z");

        assertThat(tasks.markSucceeded(id, "/files/results/" + id + "/r.png")).isEqualTo(1);
        Task done = tasks.findByIdAndUser(id, alice);
        assertThat(done.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(done.getStage()).isEqualTo(TaskStage.RESULT_ORGANIZING);
        assertThat(done.getResultUrl()).isEqualTo("/files/results/" + id + "/r.png");
        assertThat(done.getFinishedAt()).isNotNull();
        assertThat(done.getFailureCode()).isNull();
    }

    @Test
    void markFailedFromProcessingRecordsStageCodeAndReason() {
        Task task = newTask(alice, "demo-postcard");
        tasks.markProcessing(task.getId(), TaskStage.GENERATING);

        assertThat(tasks.markFailed(task.getId(), TaskStage.GENERATING, FailureCode.SIMULATED_FAILURE,
                "模拟失败：生成阶段出错")).isEqualTo(1);

        Task failed = tasks.findByIdAndUser(task.getId(), alice);
        assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.getFailedStage()).isEqualTo(TaskStage.GENERATING);
        assertThat(failed.getFailureCode()).isEqualTo(FailureCode.SIMULATED_FAILURE);
        assertThat(failed.getFailureReason()).isEqualTo("模拟失败：生成阶段出错");
        assertThat(failed.getFinishedAt()).isNotNull();
        assertThat(failed.getResultUrl()).isNull();
    }

    @Test
    void markFailedFromPendingIsAllowedWithNullStage() {
        Task task = newTask(alice, "demo-postcard");

        assertThat(tasks.markFailed(task.getId(), null, FailureCode.INTERNAL_ERROR, "系统异常")).isEqualTo(1);

        Task failed = tasks.findByIdAndUser(task.getId(), alice);
        assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.getFailedStage()).isNull();
        assertThat(failed.getFailureCode()).isEqualTo(FailureCode.INTERNAL_ERROR);
    }

    @Test
    void markSucceededRequiresProcessingInResultOrganizing() {
        Task task = newTask(alice, "demo-postcard");
        long id = task.getId();

        Map<String, Object> pending = row(id);
        assertThat(tasks.markSucceeded(id, "/files/results/x.png")).isZero();
        assertThat(row(id)).isEqualTo(pending);

        tasks.markProcessing(id, TaskStage.GENERATING);
        Map<String, Object> generating = row(id);
        assertThat(tasks.markSucceeded(id, "/files/results/x.png")).isZero();
        assertThat(row(id)).isEqualTo(generating);
    }

    @Test
    void conditionalUpdatesOnMissingTaskAffectNothing() {
        long missing = newTask(alice, "demo-postcard").getId() + 100_000;
        assertThat(tasks.markProcessing(missing, TaskStage.ASSET_CHECK)).isZero();
        assertThat(tasks.markSucceeded(missing, "/files/results/x.png")).isZero();
        assertThat(tasks.markFailed(missing, null, FailureCode.INTERNAL_ERROR, "x")).isZero();
    }

    // ---------- 终态不可变（R-05.3，T-046 第 4 项） ----------

    @FunctionalInterface
    interface Transition {
        int apply(TaskMapper mapper, long id);
    }

    static Stream<Arguments> terminalTransitions() {
        Map<String, Transition> ops = new java.util.LinkedHashMap<>();
        ops.put("markProcessing(ASSET_CHECK)", (m, id) -> m.markProcessing(id, TaskStage.ASSET_CHECK));
        ops.put("markProcessing(RESULT_ORGANIZING)", (m, id) -> m.markProcessing(id, TaskStage.RESULT_ORGANIZING));
        ops.put("markSucceeded", (m, id) -> m.markSucceeded(id, "/files/results/other.png"));
        ops.put("markFailed", (m, id) -> m.markFailed(id, TaskStage.GENERATING, FailureCode.INTERNAL_ERROR, "不应写入"));
        ops.put("markFailed(null stage)", (m, id) -> m.markFailed(id, null, FailureCode.ACCOUNT_DELETED, "不应写入"));
        ops.put("deleteById", (m, id) -> m.deleteById(id));
        return Stream.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED)
                .flatMap(terminal -> ops.entrySet().stream()
                        .map(op -> Arguments.of(terminal, op.getKey(), op.getValue())));
    }

    @ParameterizedTest(name = "{0} 任务调用 {1} → 影响 0 行且数据不变")
    @MethodSource("terminalTransitions")
    void terminalTaskIsImmutable(TaskStatus terminal, String operation, Transition transition) {
        long id = terminalTask(alice, terminal);
        addAsset(id, 0);
        Map<String, Object> before = row(id);

        assertThat(transition.apply(tasks, id)).isZero();

        assertThat(row(id)).isEqualTo(before);
        assertThat(assets.listByTaskIdsAndUser(alice, List.of(id))).hasSize(1);
    }

    @Test
    void abandonDoesNotTouchTerminalTasks() {
        long succeeded = terminalTask(alice, TaskStatus.SUCCEEDED);
        long failed = terminalTask(alice, TaskStatus.FAILED);
        Map<String, Object> s = row(succeeded);
        Map<String, Object> f = row(failed);

        assertThat(tasks.abandonActiveByUser(alice)).isZero();

        assertThat(row(succeeded)).isEqualTo(s);
        assertThat(row(failed)).isEqualTo(f);
    }

    // ---------- 删除账户时废弃（T-012 使用） ----------

    @Test
    void abandonActiveByUserOnlyAffectsThatUsersActiveTasks() {
        long pending = newTask(alice, "demo-postcard").getId();
        long processing = newTask(alice, "creative-poster").getId();
        tasks.markProcessing(processing, TaskStage.GENERATING);
        long done = terminalTask(alice, TaskStatus.SUCCEEDED);
        long bobsPending = newTask(bob, "demo-postcard").getId();
        Map<String, Object> doneBefore = row(done);
        Map<String, Object> bobBefore = row(bobsPending);

        assertThat(tasks.abandonActiveByUser(alice)).isEqualTo(2);

        Task p = tasks.findByIdAndUser(pending, alice);
        assertThat(p.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(p.getFailedStage()).isNull();
        assertThat(p.getFailureCode()).isEqualTo(FailureCode.ACCOUNT_DELETED);
        assertThat(p.getFailureReason()).isEqualTo("账户已删除，任务已废弃");
        assertThat(p.getFinishedAt()).isNotNull();

        Task g = tasks.findByIdAndUser(processing, alice);
        assertThat(g.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(g.getFailedStage()).isEqualTo(TaskStage.GENERATING);
        assertThat(g.getFailureCode()).isEqualTo(FailureCode.ACCOUNT_DELETED);

        assertThat(row(done)).isEqualTo(doneBefore);
        assertThat(row(bobsPending)).isEqualTo(bobBefore);

        // 废弃后执行器的后续条件更新全部失败（T-042 据此停止）
        assertThat(tasks.markProcessing(processing, TaskStage.RESULT_ORGANIZING)).isZero();
        assertThat(tasks.markSucceeded(processing, "/files/results/x.png")).isZero();
    }

    // ---------- 补偿删除 [OQ-12] ----------

    @Test
    void deleteByIdRemovesPendingTaskAndCascadesAssets() {
        long id = newTask(alice, "demo-postcard").getId();
        addAsset(id, 0);
        addAsset(id, 1);

        assertThat(tasks.deleteById(id)).isEqualTo(1);

        assertThat(tasks.findByIdAndUser(id, alice)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assets WHERE task_id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void deleteByIdLeavesProcessingTaskAlone() {
        long id = newTask(alice, "demo-postcard").getId();
        tasks.markProcessing(id, TaskStage.ASSET_CHECK);
        Map<String, Object> before = row(id);

        assertThat(tasks.deleteById(id)).isZero();
        assertThat(row(id)).isEqualTo(before);
    }

    // ---------- task_assets ----------

    @Test
    void assetsAreListedPerTaskInSlotOrderAndOnlyForOwner() {
        long t1 = newTask(alice, "creative-poster").getId();
        long t2 = newTask(alice, "demo-postcard").getId();
        TaskAsset t1s1 = addAsset(t1, 1);
        TaskAsset t1s0 = addAsset(t1, 0);
        TaskAsset t2s0 = addAsset(t2, 0);
        long bobs = newTask(bob, "demo-postcard").getId();
        addAsset(bobs, 0);

        List<TaskAsset> listed = assets.listByTaskIdsAndUser(alice, List.of(t2, t1, bobs));
        assertThat(listed).extracting(TaskAsset::getId)
                .containsExactly(t1s0.getId(), t1s1.getId(), t2s0.getId());

        TaskAsset first = listed.get(0);
        assertThat(first.getTaskId()).isEqualTo(t1);
        assertThat(first.getSlotIndex()).isZero();
        assertThat(first.getStorageKey()).isEqualTo(t1s0.getStorageKey());
        assertThat(first.getOriginalName()).isEqualTo("photo-0.png");
        assertThat(first.getMimeType()).isEqualTo("image/png");
        assertThat(first.getSizeBytes()).isEqualTo(1234);
        assertThat(first.getCreatedAt()).isNotNull();

        assertThat(assets.listByTaskIdsAndUser(bob, List.of(t1, t2))).isEmpty();
        assertThat(assets.listByTaskIdsAndUser(alice, List.of())).isEmpty();
    }

    @Test
    void duplicateSlotInSameTaskIsRejected() {
        long id = newTask(alice, "demo-postcard").getId();
        addAsset(id, 0);

        assertThatThrownBy(() -> addAsset(id, 0))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_task_assets_task_slot");
    }

    @Test
    void duplicateStorageKeyIsRejected() {
        long a = newTask(alice, "demo-postcard").getId();
        long b = newTask(alice, "demo-postcard").getId();
        TaskAsset first = addAsset(a, 0);

        assertThatThrownBy(() -> assets.insert(
                new TaskAsset(b, 0, first.getStorageKey(), "dup.png", "image/png", 10)))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_task_assets_storage_key");
    }

    // ---------- helpers ----------

    private Task newTask(long userId, String templateId) {
        Task task = new Task(userId, templateId, SIM);
        tasks.insert(task);
        assertThat(task.getId()).isNotNull();
        return task;
    }

    /** 只通过合法的条件更新把任务推进到终态。 */
    private long terminalTask(long userId, TaskStatus terminal) {
        long id = newTask(userId, "demo-postcard").getId();
        if (terminal == TaskStatus.SUCCEEDED) {
            assertThat(tasks.markProcessing(id, TaskStage.ASSET_CHECK)).isEqualTo(1);
            assertThat(tasks.markProcessing(id, TaskStage.GENERATING)).isEqualTo(1);
            assertThat(tasks.markProcessing(id, TaskStage.RESULT_ORGANIZING)).isEqualTo(1);
            assertThat(tasks.markSucceeded(id, "/files/results/" + id + "/ok.png")).isEqualTo(1);
        } else {
            assertThat(tasks.markProcessing(id, TaskStage.ASSET_CHECK)).isEqualTo(1);
            assertThat(tasks.markFailed(id, TaskStage.ASSET_CHECK, FailureCode.ASSET_MISSING, "素材缺失"))
                    .isEqualTo(1);
        }
        assertThat(tasks.findByIdAndUser(id, userId).getStatus()).isEqualTo(terminal);
        return id;
    }

    private TaskAsset addAsset(long taskId, int slot) {
        TaskAsset asset = new TaskAsset(taskId, slot, "uploads/2026/11/" + UUID.randomUUID() + ".png",
                "photo-" + slot + ".png", "image/png", 1234);
        assets.insert(asset);
        assertThat(asset.getId()).isNotNull();
        return asset;
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM tasks WHERE id = ?", id);
    }

    private void setCreatedAt(Task task, String utc) {
        jdbc.update("UPDATE tasks SET created_at = ? WHERE id = ?", utc, task.getId());
    }

    private static List<Long> ids(List<Task> list) {
        return list.stream().map(Task::getId).toList();
    }

    private long insertUser() {
        String name = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)",
                name, name + "@example.com", HASH);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
