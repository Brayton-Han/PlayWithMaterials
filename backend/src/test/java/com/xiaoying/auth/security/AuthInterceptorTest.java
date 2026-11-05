package com.xiaoying.auth.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xiaoying.auth.service.SessionService;
import com.xiaoying.common.error.GlobalExceptionHandler;

/**
 * 访问控制矩阵（architecture §4.3）：用桩接口验证匿名白名单返回 200、其余接口 401，
 * 有效会话时 {@link CurrentUser} 被注入。真实的模板 / 任务接口分别由 T-020 / T-041 实现。
 */
class AuthInterceptorTest {

    private SessionService sessionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        when(sessionService.resolve(any())).thenReturn(Optional.empty());
        when(sessionService.resolve("good")).thenReturn(Optional.of(new CurrentUser(7, "alice")));
        mvc = MockMvcBuilders.standaloneSetup(new StubController())
                .addMappedInterceptors(AuthInterceptor.PROTECTED_PATH_PATTERNS, new AuthInterceptor(sessionService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(DataSize.ofMegabytes(5), DataSize.ofMegabytes(20)))
                .build();
    }

    @Test
    void anonymousCanBrowseCatalog() throws Exception {
        mvc.perform(get("/api/categories")).andExpect(status().isOk());
        mvc.perform(get("/api/templates")).andExpect(status().isOk());
        mvc.perform(get("/api/templates").param("categoryId", "creative")).andExpect(status().isOk());
        mvc.perform(get("/api/templates/demo-postcard")).andExpect(status().isOk());
        verify(sessionService, never()).resolve(any());
    }

    @Test
    void anonymousCanRegisterLoginLogoutAndUseDevApi() throws Exception {
        mvc.perform(post("/api/auth/register")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isOk());
        mvc.perform(get("/api/dev/simulation")).andExpect(status().isOk());
        mvc.perform(put("/api/dev/simulation")).andExpect(status().isOk());
    }

    @Test
    void anonymousIsRejectedFromProtectedEndpoints() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tasks/42")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/account/password")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/account")).andExpect(status().isUnauthorized());
        mvc.perform(get("/files/uploads/2026/11/a.png")).andExpect(status().isUnauthorized());
        mvc.perform(get("/files/results/1/a.png")).andExpect(status().isUnauthorized());
        verify(sessionService, org.mockito.Mockito.atLeastOnce()).resolve(isNull());
    }

    @Test
    void whitelistIsMethodSpecific() throws Exception {
        // 模板只读匿名；对模板路径的写方法不在白名单中
        mvc.perform(post("/api/templates")).andExpect(status().isUnauthorized());
        // logout 只放行 POST；GET /api/auth/login 也不是匿名接口
        mvc.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidSessionIsRejected() throws Exception {
        mvc.perform(get("/api/tasks").cookie(new Cookie("sid", "expired-or-unknown")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validSessionInjectsCurrentUser() throws Exception {
        mvc.perform(get("/api/tasks").cookie(new Cookie("sid", "good")))
                .andExpect(status().isOk())
                .andExpect(content().string("7:alice"));
        mvc.perform(get("/api/auth/me").cookie(new Cookie("sid", "good")))
                .andExpect(status().isOk())
                .andExpect(content().string("7:alice"));
    }

    @RestController
    static class StubController {

        @GetMapping({"/api/categories", "/api/templates", "/api/templates/{id}", "/api/dev/simulation"})
        String anonymousGet() {
            return "ok";
        }

        @PostMapping({"/api/auth/register", "/api/auth/login", "/api/auth/logout", "/api/tasks", "/api/templates"})
        String anonymousOrProtectedPost() {
            return "ok";
        }

        @PutMapping({"/api/dev/simulation", "/api/account/password"})
        String put() {
            return "ok";
        }

        @GetMapping("/api/auth/login")
        String getLogin() {
            return "ok";
        }

        @DeleteMapping("/api/account")
        String deleteAccount() {
            return "ok";
        }

        @GetMapping({"/api/tasks", "/api/auth/me"})
        String whoAmI(CurrentUser user) {
            return user.id() + ":" + user.username();
        }

        @GetMapping("/api/tasks/{taskId}")
        String task(@PathVariable String taskId) {
            return taskId;
        }

        @GetMapping({"/files/uploads/2026/11/a.png", "/files/results/1/a.png"})
        String file() {
            return "file";
        }
    }
}
