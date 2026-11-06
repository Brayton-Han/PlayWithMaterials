package com.xiaoying.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.xiaoying.TestcontainersConfiguration;

/**
 * T-010 完成定义（backlog）与 implementation-plan §3 中 AuthControllerIT 的全部条目。
 * 测试会真实提交数据，因此每个用例使用随机用户名 / 邮箱，互不干扰，也不修改演示账户。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthControllerIT {

    private static final String PASSWORD = "Passw0rd!";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    // ---------- 注册 ----------

    @Test
    void registerCreatesUserSetsSessionCookieAndMeReturnsUser() throws Exception {
        String username = uniqueName();
        String email = username + "@Example.com";

        MvcResult result = register(username, email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").isString())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        matchesPattern("^sid=[A-Za-z0-9_-]{43};.*"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Lax"),
                        containsString("Path=/"),
                        containsString("Max-Age=604800"),
                        not(containsString("Secure")))))
                .andReturn();
        String userId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.userId");

        mvc.perform(get("/api/auth/me").cookie(sessionCookie(result)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void databaseStoresOnlyBcryptHashAndTokenHash() throws Exception {
        String username = uniqueName();
        MvcResult result = register(username, username + "@example.com", PASSWORD).andExpect(status().isCreated()).andReturn();
        String token = sessionCookie(result).getValue();

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, username);
        assertThat(hash).startsWith("$2").hasSize(60).doesNotContain(PASSWORD);

        List<String> tokenHashes = jdbc.queryForList("""
                SELECT s.token_hash FROM sessions s JOIN users u ON u.id = s.user_id WHERE u.username = ?""",
                String.class, username);
        assertThat(tokenHashes).hasSize(1);
        assertThat(tokenHashes.get(0)).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE token_hash = ?", Integer.class, token)).isZero();
    }

    @Test
    void duplicateUsernameDifferingOnlyInCaseIs409() throws Exception {
        String username = uniqueName();
        register(username, username + "@example.com", PASSWORD).andExpect(status().isCreated());

        register(username.toUpperCase(), "other-" + username + "@example.com", PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"))
                .andExpect(jsonPath("$.message").value("用户名已被使用"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void usernameOfDeletedAccountIsStillTaken() throws Exception {
        String username = uniqueName();
        register(username, username + "@example.com", PASSWORD).andExpect(status().isCreated());
        softDelete(username);

        register(username, "fresh-" + username + "@example.com", PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    void emailOfLiveAccountIsTakenCaseInsensitively() throws Exception {
        String username = uniqueName();
        register(username, username + "@example.com", PASSWORD).andExpect(status().isCreated());

        register(uniqueName(), username.toUpperCase() + "@EXAMPLE.com", PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.message").value("邮箱已被使用"));
    }

    @Test
    void emailOfDeletedAccountCanRegisterAgainAsUnrelatedUser() throws Exception {
        String oldName = uniqueName();
        String email = oldName + "@example.com";
        MvcResult old = register(oldName, email, PASSWORD).andExpect(status().isCreated()).andReturn();
        String oldId = com.jayway.jsonpath.JsonPath.read(old.getResponse().getContentAsString(), "$.userId");
        softDelete(oldName);

        String newName = uniqueName();
        MvcResult fresh = register(newName, email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(newName))
                .andReturn();
        String newId = com.jayway.jsonpath.JsonPath.read(fresh.getResponse().getContentAsString(), "$.userId");
        assertThat(newId).isNotEqualTo(oldId);
    }

    @Test
    void concurrentRegistrationOfSameUsernameYieldsExactlyOne201AndOne409() throws Exception {
        String username = uniqueName();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String email = "c" + i + "-" + username + "@example.com";
                Callable<Integer> call = () -> {
                    start.await();
                    return register(username, email, PASSWORD).andReturn().getResponse().getStatus();
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidRegistrationInputIs400WithFieldErrors() throws Exception {
        register("a-b", "not-an-email", "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors[?(@.field=='username' && @.code=='PATTERN')]").exists())
                .andExpect(jsonPath("$.details.fieldErrors[?(@.field=='email' && @.code=='FORMAT')]").exists())
                .andExpect(jsonPath("$.details.fieldErrors[?(@.field=='password' && @.code=='LENGTH')]").exists());

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fieldErrors[?(@.field=='username' && @.code=='REQUIRED')]").exists())
                .andExpect(jsonPath("$.details.fieldErrors[?(@.field=='email' && @.code=='REQUIRED')]").exists())
                .andExpect(jsonPath("$.details.fieldErrors[?(@.field=='password' && @.code=='REQUIRED')]").exists());

        register("ab", "x@example.com", PASSWORD).andExpect(status().isBadRequest());
        register("a".repeat(21), "x@example.com", PASSWORD).andExpect(status().isBadRequest());
        register(uniqueName(), "x@example.com", "p".repeat(65)).andExpect(status().isBadRequest());
        register(uniqueName(), "a".repeat(250) + "@x.com", PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    void passwordBeyondBcryptByteLimitIs400NotServerError() throws Exception {
        // 30 个汉字 = 90 字节 > BCrypt 72 字节上限，但字符数在 8 ~ 64 之内
        register(uniqueName(), uniqueName() + "@example.com", "密".repeat(30))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("LENGTH"));
    }

    // ---------- 登录 ----------

    @Test
    void seededDemoAccountCanLogIn() throws Exception {
        login("demo1", "xiaoying123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("demo1"))
                .andExpect(jsonPath("$.email").value("demo1@example.com"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")));
    }

    @Test
    void loginWithUsernameAndPasswordThenMe() throws Exception {
        String username = uniqueName();
        register(username, username + "@example.com", PASSWORD).andExpect(status().isCreated());

        MvcResult result = login(username, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username)).andReturn();
        mvc.perform(get("/api/auth/me").cookie(sessionCookie(result)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void emailCannotBeUsedToLogIn() throws Exception {
        String username = uniqueName();
        String email = username + "@example.com";
        register(username, email, PASSWORD).andExpect(status().isCreated());

        login(email, PASSWORD)
                .andExpect(status().is4xxClientError())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        login(username + "@x.io", PASSWORD)   // 不超过 20 位的邮箱形式
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void wrongPasswordAndUnknownUserAndDeletedUserGiveIdenticalResponse() throws Exception {
        String username = uniqueName();
        register(username, username + "@example.com", PASSWORD).andExpect(status().isCreated());
        String deleted = uniqueName();
        register(deleted, deleted + "@example.com", PASSWORD).andExpect(status().isCreated());
        softDelete(deleted);

        String wrongPassword = login(username, "wrong-password").andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn().getResponse().getContentAsString();
        String unknownUser = login(uniqueName(), PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String deletedUser = login(deleted, PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword)
                .contains("\"code\":\"INVALID_CREDENTIALS\"")
                .contains("用户名或密码错误")
                .isEqualTo(unknownUser)
                .isEqualTo(deletedUser);
    }

    @Test
    void loginIsCaseInsensitiveOnUsername() throws Exception {
        String username = uniqueName();
        register(username, username + "@example.com", PASSWORD).andExpect(status().isCreated());
        login(username.toUpperCase(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void blankLoginInputIs400() throws Exception {
        login("", "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void loginWhileLoggedInReplacesPreviousSession() throws Exception {
        String username = uniqueName();
        Cookie first = sessionCookie(register(username, username + "@example.com", PASSWORD).andReturn());

        MvcResult second = mvc.perform(post("/api/auth/login").cookie(first)
                        .contentType(MediaType.APPLICATION_JSON).content(loginJson(username, PASSWORD)))
                .andExpect(status().isOk()).andReturn();

        mvc.perform(get("/api/auth/me").cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(sessionCookie(second))).andExpect(status().isOk());
    }

    // ---------- 退出与会话有效性 ----------

    @Test
    void logoutInvalidatesSessionAndClearsCookie() throws Exception {
        String username = uniqueName();
        Cookie sid = sessionCookie(register(username, username + "@example.com", PASSWORD).andReturn());

        mvc.perform(post("/api/auth/logout").cookie(sid))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        matchesPattern("^sid=;.*"), containsString("Max-Age=0"), containsString("HttpOnly"))));

        mvc.perform(get("/api/auth/me").cookie(sid))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sessions s JOIN users u ON u.id = s.user_id WHERE u.username = ?""",
                Integer.class, username)).isZero();
    }

    @Test
    void logoutWithoutSessionIsIdempotent204() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").cookie(new Cookie("sid", "garbage"))).andExpect(status().isNoContent());
    }

    @Test
    void meWithoutOrWithInvalidCookieIs401() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("请先登录"));
        mvc.perform(get("/api/auth/me").cookie(new Cookie("sid", "not-a-real-token")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredSessionIs401() throws Exception {
        String username = uniqueName();
        Cookie sid = sessionCookie(register(username, username + "@example.com", PASSWORD).andReturn());
        mvc.perform(get("/api/auth/me").cookie(sid)).andExpect(status().isOk());

        jdbc.update("""
                UPDATE sessions s JOIN users u ON u.id = s.user_id
                SET s.expires_at = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE u.username = ?""", username);

        mvc.perform(get("/api/auth/me").cookie(sid))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void sessionOfDeletedUserIs401() throws Exception {
        String username = uniqueName();
        Cookie sid = sessionCookie(register(username, username + "@example.com", PASSWORD).andReturn());
        softDelete(username);

        mvc.perform(get("/api/auth/me").cookie(sid)).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionExpiresSevenDaysAfterCreation() throws Exception {
        String username = uniqueName();
        register(username, username + "@example.com", PASSWORD).andExpect(status().isCreated());
        Long seconds = jdbc.queryForObject("""
                SELECT TIMESTAMPDIFF(SECOND, s.created_at, s.expires_at)
                FROM sessions s JOIN users u ON u.id = s.user_id WHERE u.username = ?""", Long.class, username);
        assertThat(seconds).isEqualTo(7L * 24 * 3600);
    }

    // ---------- 访问控制矩阵（architecture §4.3） ----------

    @Test
    void anonymousTemplateEndpointsAre200() throws Exception {
        // 模板接口已由 T-020 实现，匿名访问应直接 200（更多用例见 CatalogControllerIT）
        mvc.perform(get("/api/templates")).andExpect(status().isOk());
        mvc.perform(get("/api/templates/demo-postcard")).andExpect(status().isOk());
        mvc.perform(get("/api/categories")).andExpect(status().isOk());
    }

    @Test
    void anonymousTaskAndAccountEndpointsAre401() throws Exception {
        mvc.perform(get("/api/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/tasks/42")).andExpect(status().isUnauthorized());
        // POST /api/tasks（T-041）不限定 consumes，非 multipart 的匿名请求同样先得到 401
        mvc.perform(post("/api/tasks")).andExpect(status().isUnauthorized());
        // 账户接口（T-012）：真实路由与方法；不支持的方法会先得到 405（路由先于鉴权拦截器）
        mvc.perform(put("/api/account/password").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/account").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/files/uploads/2026/11/x.png")).andExpect(status().isUnauthorized());
        mvc.perform(get("/files/results/1/x.png")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownApiRouteIs401WhenAnonymousAnd404WhenLoggedIn() throws Exception {
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized());

        String username = uniqueName();
        Cookie sid = sessionCookie(register(username, username + "@example.com", PASSWORD).andReturn());
        mvc.perform(get("/api/does-not-exist").cookie(sid))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---------- helpers ----------

    private ResultActions register(String username, String email, String password) throws Exception {
        String body = "{\"username\":%s,\"email\":%s,\"password\":%s}".formatted(json(username), json(email), json(password));
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(username, password)));
    }

    private static String loginJson(String username, String password) {
        return "{\"username\":%s,\"password\":%s}".formatted(json(username), json(password));
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Cookie sessionCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("sid");
        assertThat(cookie).as("Set-Cookie sid").isNotNull();
        return new Cookie("sid", cookie.getValue());
    }

    private void softDelete(String username) {
        jdbc.update("UPDATE users SET deleted_at = UTC_TIMESTAMP(3) WHERE username = ?", username);
    }

    /** 3 ~ 20 位字母数字下划线。 */
    private static String uniqueName() {
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
