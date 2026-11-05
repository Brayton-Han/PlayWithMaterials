package com.xiaoying.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.xiaoying.TestcontainersConfiguration;

/**
 * T-004：Flyway V1 / V2 在真实 MySQL 8.4 上执行成功，database.md §2 中的约束全部生效。
 * 每个测试方法在事务中执行并回滚，不影响种子数据。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class MigrationIT {

    private static final String HASH = "$2a$10$BpxBLDiVUYoM.u0vpiOWEuR1mpe3GVxuFy0hTByNsvFr8zfdU1Ts6";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliedV1AndV2() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2");
    }

    @Test
    void allTablesUseUtf8mb4AiCiCollation() {
        List<String> collations = jdbc.queryForList("""
                SELECT DISTINCT table_collation FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name IN
                  ('users','sessions','categories','templates','tasks','task_assets')""", String.class);
        assertThat(collations).containsExactly("utf8mb4_0900_ai_ci");
    }

    @Test
    void stepLogTableIsNotCreatedYet() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'task_step_logs'""", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void uniqueIndexesExist() {
        List<String> uniques = jdbc.queryForList("""
                SELECT DISTINCT CONCAT(table_name, '.', index_name) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND non_unique = 0 AND index_name <> 'PRIMARY'""", String.class);
        assertThat(uniques).contains(
                "users.uk_users_username",
                "users.uk_users_active_email",
                "sessions.uk_sessions_token_hash",
                "categories.uk_categories_name",
                "task_assets.uk_task_assets_task_slot",
                "task_assets.uk_task_assets_storage_key");
    }

    @Test
    void secondaryIndexesExist() {
        List<String> indexes = jdbc.queryForList("""
                SELECT DISTINCT CONCAT(table_name, '.', index_name) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND non_unique = 1""", String.class);
        assertThat(indexes).contains(
                "sessions.idx_sessions_user_id",
                "templates.idx_templates_category_sort",
                "tasks.idx_tasks_user_created",
                "tasks.idx_tasks_user_template_created",
                "tasks.idx_tasks_template_id",
                "tasks.idx_tasks_status");
    }

    @Test
    void checkConstraintsExist() {
        List<String> checks = jdbc.queryForList("""
                SELECT constraint_name FROM information_schema.check_constraints
                WHERE constraint_schema = DATABASE()""", String.class);
        assertThat(checks).contains(
                "chk_users_username",
                "chk_templates_image_count",
                "chk_templates_max_size",
                "chk_templates_mime_types",
                "chk_tasks_status",
                "chk_tasks_stage",
                "chk_tasks_failed_stage",
                "chk_tasks_succeeded",
                "chk_tasks_failed",
                "chk_tasks_terminal_time",
                "chk_task_assets_size");
    }

    @Test
    void foreignKeysExist() {
        List<String> fks = jdbc.queryForList("""
                SELECT constraint_name FROM information_schema.referential_constraints
                WHERE constraint_schema = DATABASE()""", String.class);
        assertThat(fks).contains(
                "fk_sessions_users", "fk_templates_categories", "fk_tasks_users",
                "fk_tasks_templates", "fk_task_assets_tasks");
    }

    // ---------- 种子数据 ----------

    @Test
    void seedDataIsPresent() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM categories", Integer.class)).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM templates", Integer.class)).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForList("SELECT DISTINCT required_image_count FROM templates ORDER BY 1", Integer.class))
                .contains(1, 2, 3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM templates WHERE accepted_mime_types = JSON_ARRAY('image/png')", Integer.class))
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM templates WHERE max_file_size_bytes = 1048576", Integer.class))
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForList("SELECT password_hash FROM users WHERE username IN ('demo1','demo2')", String.class))
                .hasSize(2)
                .allSatisfy(hash -> assertThat(hash).startsWith("$2").hasSize(60));
    }

    // ---------- 约束行为 ----------

    @Test
    void succeededTaskWithoutResultUrlIsRejected() {
        long userId = insertUser("checker", "checker@example.com");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO tasks (user_id, template_id, status, stage, sim_config, finished_at)
                VALUES (?, 'demo-postcard', 'SUCCEEDED', 'RESULT_ORGANIZING', JSON_OBJECT(), NOW(3))""", userId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_tasks_succeeded");
    }

    @Test
    void failedTaskWithoutReasonIsRejected() {
        long userId = insertUser("failer", "failer@example.com");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO tasks (user_id, template_id, status, sim_config, finished_at)
                VALUES (?, 'demo-postcard', 'FAILED', JSON_OBJECT(), NOW(3))""", userId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_tasks_failed");
    }

    @Test
    void unknownTaskStatusIsRejected() {
        long userId = insertUser("statuser", "statuser@example.com");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO tasks (user_id, template_id, status, sim_config)
                VALUES (?, 'demo-postcard', 'CANCELLED', JSON_OBJECT())""", userId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_tasks_status");
    }

    @Test
    void usernameDifferingOnlyInCaseIsRejected() {
        assertThatThrownBy(() -> insertUser("DEMO1", "someone-else@example.com"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_users_username");
    }

    @Test
    void invalidUsernameIsRejectedByCheck() {
        assertThatThrownBy(() -> insertUser("a-b", "dash@example.com"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_users_username");
    }

    @Test
    void activeEmailIsCaseInsensitiveUniqueAmongLiveUsers() {
        assertThatThrownBy(() -> insertUser("demo1_copy", "DEMO1@Example.com"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_users_active_email");
    }

    @Test
    void activeEmailBecomesNullAfterSoftDeleteAndEmailCanBeReused() {
        long id = insertUser("leaver", "Leaver@Example.com");
        assertThat(jdbc.queryForObject("SELECT active_email FROM users WHERE id = ?", String.class, id))
                .isEqualTo("leaver@example.com");

        jdbc.update("UPDATE users SET deleted_at = NOW(3) WHERE id = ?", id);
        assertThat(jdbc.queryForObject("SELECT active_email FROM users WHERE id = ?", String.class, id)).isNull();

        // 已删除账户的邮箱可以再注册；用户名仍被永久占用（OQ-31）
        insertUser("returner", "leaver@example.com");
        assertThatThrownBy(() -> insertUser("Leaver", "new@example.com"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_users_username");
    }

    @Test
    void emptyAssetIsRejected() {
        long userId = insertUser("uploader", "uploader@example.com");
        jdbc.update("INSERT INTO tasks (user_id, template_id, sim_config) VALUES (?, 'demo-postcard', JSON_OBJECT())", userId);
        Long taskId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task_assets (task_id, slot_index, storage_key, original_name, mime_type, size_bytes)
                VALUES (?, 0, 'uploads/2026/11/x.png', 'x.png', 'image/png', 0)""", taskId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_task_assets_size");
    }

    private long insertUser(String username, String email) {
        jdbc.update("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)", username, email, HASH);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
