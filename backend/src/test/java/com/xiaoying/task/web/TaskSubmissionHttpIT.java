package com.xiaoying.task.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.xiaoying.TestcontainersConfiguration;
import com.xiaoying.auth.security.SessionCookies;
import com.xiaoying.auth.service.SessionService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 经真实 Tomcat 的 {@code POST /api/tasks}（MockMvc 不执行 multipart 上限，也不走真实的 multipart 解析）：
 * 真实 multipart 请求能被受理并保存文件；单个文件超过全局上限（5 MB）→ 413 {@code PAYLOAD_TOO_LARGE}，
 * 没有任务记录、没有残留文件；未登录 401。派发器使用真实 bean（T-041 阶段只记录日志）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TaskSubmissionHttpIT {

    private static final String HASH = "$2a$10$BpxBLDiVUYoM.u0vpiOWEuR1mpe3GVxuFy0hTByNsvFr8zfdU1Ts6";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @Value("${app.storage.root}")
    Path storageRoot;

    final HttpClient http = HttpClient.newHttpClient();

    long user;
    String token;
    Set<Path> filesBefore;

    @BeforeEach
    void setUp() throws IOException {
        String name = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)",
                name, name + "@example.com", HASH);
        user = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, name);
        token = sessions.create(user);
        filesBefore = storedFiles();
    }

    @Test
    void realMultipartRequestIsAcceptedAndFilesAreStored() throws Exception {
        HttpResponse<String> res = post(token, "demo-postcard",
                new Part("a.jpg", "image/jpeg", TaskSubmissionIT.jpeg(4096)),
                new Part("b.png", "image/png", TaskSubmissionIT.png(4096)));

        assertThat(res.statusCode()).isEqualTo(202);
        assertThat(res.headers().firstValue("Location")).hasValueSatisfying(v -> assertThat(v).matches("/api/tasks/\\d+"));
        JsonNode body = JSON.readTree(res.body());
        assertThat(body.get("status").asString()).isEqualTo("PENDING");
        assertThat(body.get("inputAssets").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id = ?", Long.class, user)).isEqualTo(1);
        assertThat(newFiles()).hasSize(2);
    }

    @Test
    void fileOverTheGlobalLimitIs413AndLeavesNothing() throws Exception {
        HttpResponse<String> res = post(token, "creative-poster",
                new Part("huge.png", "image/png", TaskSubmissionIT.png(5 * 1024 * 1024 + 1024)));

        assertThat(res.statusCode()).isEqualTo(413);
        JsonNode body = JSON.readTree(res.body());
        assertThat(body.get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.get("details").get("maxFileSizeBytes").asLong()).isEqualTo(5L * 1024 * 1024);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id = ?", Long.class, user)).isZero();
        assertThat(newFiles()).isEmpty();
    }

    @Test
    void anonymousIs401() throws Exception {
        HttpResponse<String> res = post(null, "creative-poster",
                new Part("a.jpg", "image/jpeg", TaskSubmissionIT.jpeg(100)));

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(res.body()).get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(newFiles()).isEmpty();
    }

    // ---------- helpers ----------

    private record Part(String fileName, String contentType, byte[] content) {
    }

    private HttpResponse<String> post(String sessionToken, String templateId, Part... parts)
            throws IOException, InterruptedException {
        String boundary = "----t041" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"templateId\"\r\n\r\n"
                + templateId + "\r\n");
        for (Part p : parts) {
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                    + p.fileName() + "\"\r\nContent-Type: " + p.contentType() + "\r\n\r\n");
            out.write(p.content());
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "--\r\n");

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/tasks"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        if (sessionToken != null) {
            req.header("Cookie", SessionCookies.NAME + "=" + sessionToken);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private Set<Path> newFiles() throws IOException {
        Set<Path> now = storedFiles();
        now.removeAll(filesBefore);
        return now;
    }

    private Set<Path> storedFiles() throws IOException {
        if (!Files.exists(storageRoot)) {
            return new HashSet<>();
        }
        try (Stream<Path> s = Files.walk(storageRoot)) {
            return s.filter(Files::isRegularFile).collect(Collectors.toCollection(HashSet::new));
        }
    }
}
