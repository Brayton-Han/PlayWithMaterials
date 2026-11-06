package com.xiaoying.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.xiaoying.TestcontainersConfiguration;

/**
 * T-012 完成定义（backlog）与 implementation-plan §3 中 AccountControllerIT 的全部条目（F-28）。
 * 测试真实提交数据，每个用例使用随机用户名 / 邮箱，互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccountControllerIT {

    private static final String PASSWORD = "Passw0rd!";
    private static final String NEW_PASSWORD = "N3wPassw0rd!";
    private static final String SIM = """
            {"assetCheckMs":1500,"generatingMs":3000,"resultOrganizingMs":1500,"failStage":null,"failReason":null}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    // ---------- 修改密码 ----------

    @Test
    void wrongCurrentPasswordIs400NotUnauthenticatedAndPasswordUnchanged() throws Exception {
        String username = uniqueName();
        Cookie sid = registerAndGetCookie(username);
        String hashBefore = passwordHash(username);

        changePassword(sid, "WrongPassw0rd", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"))
                .andExpect(jsonPath("$.message").value("当前密码不正确"));

        assertThat(passwordHash(username)).isEqualTo(hashBefore);
        mvc.perform(get("/api/auth/me").cookie(sid)).andExpect(status().isOk());
        login(username, PASSWORD).andExpect(status().isOk());
        login(username, NEW_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void changePasswordThenOldPasswordFailsAndNewPasswordWorks() throws Exception {
        String username = uniqueName();
        Cookie sid = registerAndGetCookie(username);

        changePassword(sid, PASSWORD, NEW_PASSWORD)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        login(username, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login(username, NEW_PASSWORD).andExpect(status().isOk());
        String hash = passwordHash(username);
        assertThat(hash).startsWith("$2").hasSize(60).doesNotContain(NEW_PASSWORD);
    }

    @Test
    void changePasswordRevokesOtherSessionsButKeepsCurrentOne() throws Exception {
        String username = uniqueName();
        Cookie current = registerAndGetCookie(username);
        Cookie otherDevice = sessionCookie(login(username, PASSWORD).andExpect(status().isOk()).andReturn());
        Cookie thirdDevice = sessionCookie(login(username, PASSWORD).andExpect(status().isOk()).andReturn());
        // 另一个用户的会话不受影响
        String bystander = uniqueName();
        Cookie bystanderSid = registerAndGetCookie(bystander);

        changePassword(current, PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").cookie(current)).andExpect(status().isOk());
        for (Cookie revoked : List.of(otherDevice, thirdDevice)) {
            mvc.perform(get("/api/auth/me").cookie(revoked))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        assertThat(sessionCount(username)).isEqualTo(1);
        mvc.perform(get("/api/auth/me").cookie(bystanderSid)).andExpect(status().isOk());
        assertThat(sessionCount(bystander)).isEqualTo(1);
    }

    @Test
    void invalidChangePasswordInputIs400WithFieldErrors() throws Exception {
        Cookie sid = registerAndGetCookie(uniqueName());

        changePassword(sid, PASSWORD, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("newPassword"));
        changePassword(sid, PASSWORD, "x".repeat(65))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("newPassword"));
        changePassword(sid, "", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("currentPassword"));
        mvc.perform(put("/api/account/password").cookie(sid).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(put("/api/account/password").cookie(sid).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void newPasswordBeyondBcryptByteLimitIs400AndPasswordUnchanged() throws Exception {
        String username = uniqueName();
        Cookie sid = registerAndGetCookie(username);
        String hashBefore = passwordHash(username);

        // 30 个汉字 = 30 字符（≤ 64）但 90 字节（> 72）
        changePassword(sid, PASSWORD, "密".repeat(30))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("newPassword"));
        assertThat(passwordHash(username)).isEqualTo(hashBefore);
    }

    // ---------- 删除账户：确认文字 ----------

    @ParameterizedTest
    @ValueSource(strings = {"delete", "DELETE ", " DELETE", "DELET", "", "Delete"})
    void wrongConfirmTextIs400AndNothingChanges(String confirmText) throws Exception {
        String username = uniqueName();
        Cookie sid = registerAndGetCookie(username);
        long userId = userId(username);
        long taskId = insertTask(userId, "PENDING", null);

        deleteAccount(sid, confirmText)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIRM_TEXT_MISMATCH"))
                .andExpect(jsonPath("$.message").value("请输入 DELETE 以确认删除"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Object.class, userId)).isNull();
        assertThat(taskRow(taskId).get("status")).isEqualTo("PENDING");
        mvc.perform(get("/api/auth/me").cookie(sid)).andExpect(status().isOk());
    }

    @Test
    void missingConfirmTextOrBodyIs400() throws Exception {
        String username = uniqueName();
        Cookie sid = registerAndGetCookie(username);

        mvc.perform(delete("/api/account").cookie(sid).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("confirmText"));
        mvc.perform(delete("/api/account").cookie(sid))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/auth/me").cookie(sid)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE username = ?", Object.class, username))
                .isNull();
    }

    // ---------- 删除账户：成功 ----------

    @Test
    void deleteAccountSoftDeletesClearsCookieAndRevokesAllSessions() throws Exception {
        String username = uniqueName();
        String email = username + "@example.com";
        Cookie current = registerAndGetCookie(username);
        Cookie otherDevice = sessionCookie(login(username, PASSWORD).andExpect(status().isOk()).andReturn());

        deleteAccount(current, "DELETE")
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("sid="), containsString("Max-Age=0"), containsString("HttpOnly"),
                        containsString("Path=/"))));

        Map<String, Object> user = jdbc.queryForMap(
                "SELECT username, email, active_email, deleted_at FROM users WHERE username = ?", username);
        assertThat(user.get("deleted_at")).isNotNull();
        assertThat(user.get("email")).isEqualTo(email);
        assertThat(user.get("active_email")).isNull();
        assertThat(sessionCount(username)).isZero();

        for (Cookie old : List.of(current, otherDevice)) {
            mvc.perform(get("/api/auth/me").cookie(old))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        // 原 Cookie 再次删除 / 修改密码 → 401
        deleteAccount(current, "DELETE").andExpect(status().isUnauthorized());
        changePassword(current, PASSWORD, NEW_PASSWORD).andExpect(status().isUnauthorized());
        login(username, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void deleteAbandonsActiveTasksKeepsFinishedOnesAndRetainsAllRows() throws Exception {
        String username = uniqueName();
        Cookie sid = registerAndGetCookie(username);
        long userId = userId(username);
        long pending = insertTask(userId, "PENDING", null);
        long processing = insertTask(userId, "PROCESSING", "GENERATING");
        long succeeded = insertTask(userId, "SUCCEEDED", "RESULT_ORGANIZING");
        long failed = insertTask(userId, "FAILED", "ASSET_CHECK");
        Map<String, Object> succeededBefore = taskRow(succeeded);
        Map<String, Object> failedBefore = taskRow(failed);

        // 另一个用户进行中的任务和会话不受影响
        String other = uniqueName();
        Cookie otherSid = registerAndGetCookie(other);
        long otherUserId = userId(other);
        long otherProcessing = insertTask(otherUserId, "PROCESSING", "GENERATING");
        Map<String, Object> otherBefore = taskRow(otherProcessing);

        deleteAccount(sid, "DELETE").andExpect(status().isNoContent());

        Map<String, Object> p = taskRow(pending);
        assertThat(p.get("status")).isEqualTo("FAILED");
        assertThat(p.get("failure_code")).isEqualTo("ACCOUNT_DELETED");
        assertThat(p.get("failure_reason")).isEqualTo("账户已删除，任务已废弃");
        assertThat(p.get("failed_stage")).isNull();
        assertThat(p.get("finished_at")).isNotNull();
        assertThat(p.get("result_url")).isNull();

        Map<String, Object> g = taskRow(processing);
        assertThat(g.get("status")).isEqualTo("FAILED");
        assertThat(g.get("failure_code")).isEqualTo("ACCOUNT_DELETED");
        assertThat(g.get("failed_stage")).isEqualTo("GENERATING");
        assertThat(g.get("stage")).isEqualTo("GENERATING");
        assertThat(g.get("result_url")).isNull();

        assertThat(taskRow(succeeded)).isEqualTo(succeededBefore);
        assertThat(taskRow(failed)).isEqualTo(failedBefore);

        // 不删除任何任务和素材
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_assets a JOIN tasks t ON t.id = a.task_id WHERE t.user_id = ?""",
                Integer.class, userId)).isEqualTo(4);

        assertThat(taskRow(otherProcessing)).isEqualTo(otherBefore);
        mvc.perform(get("/api/auth/me").cookie(otherSid)).andExpect(status().isOk());
        mvc.perform(get("/api/tasks/{id}", otherProcessing).cookie(otherSid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void emailOfDeletedAccountCanRegisterAgainButUsernameCannot() throws Exception {
        String username = uniqueName();
        String email = username + "@example.com";
        Cookie sid = registerAndGetCookie(username);
        long oldUserId = userId(username);
        long oldTask = insertTask(oldUserId, "SUCCEEDED", "RESULT_ORGANIZING");
        deleteAccount(sid, "DELETE").andExpect(status().isNoContent());

        register(username, "fresh-" + email, PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));

        String newName = uniqueName();
        Cookie fresh = sessionCookie(register(newName, email, PASSWORD).andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/tasks").cookie(fresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/tasks/{id}", oldTask).cookie(fresh))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    // ---------- 未登录 ----------

    @Test
    void anonymousOrInvalidSessionIs401() throws Exception {
        for (Cookie cookie : new Cookie[] {null, new Cookie("sid", "not-a-real-token")}) {
            ResultActions change = mvc.perform(withCookie(put("/api/account/password"), cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changePasswordJson(PASSWORD, NEW_PASSWORD)));
            change.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

            ResultActions del = mvc.perform(withCookie(delete("/api/account"), cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"confirmText\":\"DELETE\"}"));
            del.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void unsupportedMethodOnAccountIs405WhenLoggedIn() throws Exception {
        Cookie sid = registerAndGetCookie(uniqueName());
        mvc.perform(post("/api/account").cookie(sid))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    // ---------- helpers ----------

    private static MockHttpServletRequestBuilder withCookie(MockHttpServletRequestBuilder builder, Cookie cookie) {
        return cookie == null ? builder : builder.cookie(cookie);
    }

    private ResultActions changePassword(Cookie sid, String current, String next) throws Exception {
        return mvc.perform(put("/api/account/password").cookie(sid).contentType(MediaType.APPLICATION_JSON)
                .content(changePasswordJson(current, next)));
    }

    private static String changePasswordJson(String current, String next) {
        return "{\"currentPassword\":%s,\"newPassword\":%s}".formatted(json(current), json(next));
    }

    private ResultActions deleteAccount(Cookie sid, String confirmText) throws Exception {
        return mvc.perform(delete("/api/account").cookie(sid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmText\":%s}".formatted(json(confirmText))));
    }

    private ResultActions register(String username, String email, String password) throws Exception {
        String body = "{\"username\":%s,\"email\":%s,\"password\":%s}".formatted(json(username), json(email), json(password));
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Cookie registerAndGetCookie(String username) throws Exception {
        return sessionCookie(register(username, username + "@example.com", PASSWORD)
                .andExpect(status().isCreated()).andReturn());
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":%s,\"password\":%s}".formatted(json(username), json(password))));
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Cookie sessionCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("sid");
        assertThat(cookie).as("Set-Cookie sid").isNotNull();
        return new Cookie("sid", cookie.getValue());
    }

    private String passwordHash(String username) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, username);
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private int sessionCount(String username) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sessions s JOIN users u ON u.id = s.user_id WHERE u.username = ?",
                Integer.class, username);
    }

    /** 直接写入指定状态的任务（满足 chk_tasks_* 约束）+ 1 条素材记录。 */
    private long insertTask(long userId, String status, String stage) {
        String resultUrl = "SUCCEEDED".equals(status) ? "/files/results/x/" + UUID.randomUUID() + ".png" : null;
        boolean failed = "FAILED".equals(status);
        boolean finished = failed || "SUCCEEDED".equals(status);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO tasks (user_id, template_id, status, stage, failed_stage, failure_code, failure_reason,
                                       result_url, sim_config, started_at, finished_at)
                    VALUES (?, 'creative-poster', ?, ?, ?, ?, ?, ?, CAST(? AS JSON),
                            IF(? IS NULL, NULL, NOW(3)), IF(?, NOW(3), NULL))""", Statement.RETURN_GENERATED_KEYS);
            Object[] args = {userId, status, stage, failed ? stage : null, failed ? "SIMULATED_FAILURE" : null,
                    failed ? "模拟失败" : null, resultUrl, SIM, stage, finished};
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        long taskId = keys.getKey().longValue();
        jdbc.update("""
                INSERT INTO task_assets (task_id, slot_index, storage_key, original_name, mime_type, size_bytes)
                VALUES (?, 0, ?, 'a.jpg', 'image/jpeg', 1024)""",
                taskId, "uploads/2026/11/" + UUID.randomUUID() + ".jpg");
        return taskId;
    }

    private Map<String, Object> taskRow(long taskId) {
        return jdbc.queryForMap("SELECT * FROM tasks WHERE id = ?", taskId);
    }

    private static String uniqueName() {
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
