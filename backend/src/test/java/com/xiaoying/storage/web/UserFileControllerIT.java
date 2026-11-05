package com.xiaoying.storage.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.xiaoying.TestcontainersConfiguration;
import com.xiaoying.auth.security.SessionCookies;
import com.xiaoying.auth.service.SessionService;
import com.xiaoying.task.mapper.TaskMapper;
import com.xiaoying.task.pipeline.TaskDispatcher;
import com.xiaoying.task.pipeline.TaskPipeline;

import tools.jackson.databind.json.JsonMapper;

/**
 * T-047 完成定义（backlog）/ implementation-plan T-047：{@code /files/uploads/**}、{@code /files/results/**}
 * 本人 200（Content-Type、内容、{@code Cache-Control: private, no-store}）；匿名 / 无效会话 401；他人 404；
 * 不存在 404；路径参数不符合格式 404；删除账户后本人原 Cookie 401、其他用户 404，文件仍在磁盘上。
 * <p>
 * 任务经真实接口提交（派发器被替换），结果图由真实 pipeline 在测试线程中同步生成。
 */
@SpringBootTest(properties = {
        "app.storage.root=${java.io.tmpdir}/xiaoying-user-file-it",
        "app.simulation.asset-check-ms=0",
        "app.simulation.generating-ms=0",
        "app.simulation.result-organizing-ms=0"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UserFileControllerIT {

    private static final String HASH = "$2a$10$BpxBLDiVUYoM.u0vpiOWEuR1mpe3GVxuFy0hTByNsvFr8zfdU1Ts6";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String POSTCARD = "demo-postcard";   // 2 张，jpeg / png
    private static final String CACHE_CONTROL = "private, no-store";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @Autowired
    TaskPipeline pipeline;

    @Autowired
    TaskMapper taskMapper;

    @MockitoBean
    TaskDispatcher dispatcher;

    @Value("${app.storage.root}")
    Path storageRoot;

    long ownerId;
    Cookie owner;
    Cookie other;

    final byte[] jpeg = jpeg(2048);
    final byte[] png = png(1024);

    @BeforeEach
    void setUp() {
        ownerId = insertUser();
        owner = cookieFor(ownerId);
        other = cookieFor(insertUser());
    }

    // ---------- 本人 200 ----------

    @Test
    void ownerCanReadEachUploadedAssetWithItsTypeAndNoStore() throws Exception {
        long taskId = submit(owner);
        List<String> urls = assetUrls(taskId);

        mvc.perform(get(URI.create(urls.get(0))).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Cache-Control", CACHE_CONTROL))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().longValue("Content-Length", jpeg.length))
                .andExpect(content().bytes(jpeg));
        mvc.perform(get(URI.create(urls.get(1))).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", CACHE_CONTROL))
                .andExpect(content().bytes(png));
    }

    @Test
    void assetUrlsReturnedByTheTaskApiAreReadable() throws Exception {
        long taskId = submit(owner);
        String body = mvc.perform(get("/api/tasks/{id}", taskId).cookie(owner))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (var asset : JSON.readTree(body).get("inputAssets")) {
            mvc.perform(get(URI.create(asset.get("url").asString())).cookie(owner)).andExpect(status().isOk());
        }
    }

    @Test
    void ownerCanReadTheResultImage() throws Exception {
        long taskId = submit(owner);
        pipeline.run(taskId);
        String resultUrl = resultUrl(taskId);
        assertThat(resultUrl).matches("^/files/results/%d/[0-9a-f-]{36}\\.png$".formatted(taskId));

        mvc.perform(get(URI.create(resultUrl)).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", CACHE_CONTROL))
                .andExpect(content().bytes(classpathBytes("seed/results/postcard.png")));
    }

    @Test
    void headRequestIsServedWithoutBody() throws Exception {
        long taskId = submit(owner);
        mvc.perform(head(URI.create(assetUrls(taskId).getFirst())).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Cache-Control", CACHE_CONTROL));
    }

    // ---------- 匿名 / 无效会话 401 ----------

    @Test
    void anonymousAndInvalidSessionsGet401() throws Exception {
        long taskId = submit(owner);
        pipeline.run(taskId);
        Cookie bogus = new Cookie(SessionCookies.NAME, "x".repeat(43));
        for (String url : List.of(assetUrls(taskId).getFirst(), resultUrl(taskId))) {
            unauthenticated(mvc.perform(get(URI.create(url))));
            unauthenticated(mvc.perform(get(URI.create(url)).cookie(bogus)));
        }
        // 格式不对 / 不存在的路径同样先要求登录，不泄露任何信息
        unauthenticated(mvc.perform(get("/files/uploads/2026/11/nope.jpg")));
        unauthenticated(mvc.perform(get("/files/results/1/" + UUID.randomUUID() + ".png")));
    }

    // ---------- 他人 / 不存在 404 ----------

    @Test
    void otherUserGets404ForUploadsAndResults() throws Exception {
        long taskId = submit(owner);
        pipeline.run(taskId);
        for (String url : List.of(assetUrls(taskId).get(0), assetUrls(taskId).get(1), resultUrl(taskId))) {
            notFound(mvc.perform(get(URI.create(url)).cookie(other)));
        }
    }

    @Test
    void wellFormedButUnknownPathsGet404() throws Exception {
        long taskId = submit(owner);
        pipeline.run(taskId);
        String uploadDir = assetUrls(taskId).getFirst().replaceAll("/[^/]+$", "/");

        notFound(mvc.perform(get(URI.create(uploadDir + UUID.randomUUID() + ".jpg")).cookie(owner)));
        notFound(mvc.perform(get(URI.create("/files/uploads/1999/01/" + UUID.randomUUID() + ".png")).cookie(owner)));
        // 本人的任务，但文件名与 result_url 不一致
        notFound(mvc.perform(get("/files/results/{t}/{f}", taskId, UUID.randomUUID() + ".png").cookie(owner)));
        // 本人的结果文件名搭配不存在的任务 ID
        String fileName = resultUrl(taskId).replaceAll("^.*/", "");
        notFound(mvc.perform(get("/files/results/{t}/{f}", taskId + 1000, fileName).cookie(owner)));
        notFound(mvc.perform(get("/files/results/9223372036854775807/{f}", fileName).cookie(owner)));
    }

    @Test
    void resultOfAnUnfinishedTaskIsNotReadable() throws Exception {
        long taskId = submit(owner);   // 仍是 PENDING，result_url 为 NULL
        notFound(mvc.perform(get("/files/results/{t}/{f}", taskId, UUID.randomUUID() + ".png").cookie(owner)));
    }

    @Test
    void resultFileOnDiskWithoutMatchingRecordIsNotServed() throws Exception {
        long taskId = submit(owner);   // 未执行：磁盘上手动放一个文件，数据库没有对应 result_url
        String fileName = UUID.randomUUID() + ".png";
        Path planted = storageRoot.resolve("results").resolve(Long.toString(taskId)).resolve(fileName);
        Files.createDirectories(planted.getParent());
        Files.write(planted, png);

        notFound(mvc.perform(get("/files/results/{t}/{f}", taskId, fileName).cookie(owner)));
    }

    @Test
    void recordWhoseFileIsMissingOnDiskGets404() throws Exception {
        long taskId = submit(owner);
        pipeline.run(taskId);
        String assetUrl = assetUrls(taskId).getFirst();
        Files.delete(storageRoot.resolve(assetUrl.substring("/files/".length())));
        Files.delete(storageRoot.resolve(resultUrl(taskId).substring("/files/".length())));

        notFound(mvc.perform(get(URI.create(assetUrl)).cookie(owner)));
        notFound(mvc.perform(get(URI.create(resultUrl(taskId))).cookie(owner)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/files/uploads/26/11/3f2c9a1e-7b7d-4a51-9b3e-0c1d2e3f4a5b.jpg",          // yyyy 非 4 位
            "/files/uploads/2026/1/3f2c9a1e-7b7d-4a51-9b3e-0c1d2e3f4a5b.jpg",         // mm 非 2 位
            "/files/uploads/2026/ab/3f2c9a1e-7b7d-4a51-9b3e-0c1d2e3f4a5b.jpg",
            "/files/uploads/2026/11/3F2C9A1E-7B7D-4A51-9B3E-0C1D2E3F4A5B.jpg",        // 大写
            "/files/uploads/2026/11/3f2c9a1e-7b7d-4a51-9b3e-0c1d2e3f4a5b.gif",        // 扩展名
            "/files/uploads/2026/11/3f2c9a1e-7b7d-4a51-9b3e-0c1d2e3f4a5b.JPG",
            "/files/uploads/2026/11/3f2c9a1e-7b7d-4a51-9b3e-0c1d2e3f4a5b.jpg.part",
            "/files/uploads/2026/11/short.jpg",
            "/files/uploads/2026/11/..%2F..%2F..%2Fapplication.yml",
            "/files/uploads/2026/11/%2e%2e",
            "/files/uploads/2026/11",                                                 // 段数不对
            "/files/uploads/2026/11/x/3f2c9a1e-7b7d-4a51-9b3e-0c1d2e3f4a5b.jpg",
            "/files/results/abc/3f2c9a1e-7b7d-4a51-9b3e-0c1d2e3f4a5b.png",            // taskId 非数字
            "/files/results/-1/3f2c9a1e-7b7d-4a51-9b3e-0c1d2e3f4a5b.png",
            "/files/results/12345678901234567890/3f2c9a1e-7b7d-4a51-9b3e-0c1d2e3f4a5b.png",  // 20 位
            "/files/results/9999999999999999999/3f2c9a1e-7b7d-4a51-9b3e-0c1d2e3f4a5b.png",   // 超出 long
            "/files/results/1/..%2F..%2Fuploads",
            "/files/results/1"})
    void malformedPathsGet404(String path) throws Exception {
        notFound(mvc.perform(get(URI.create(path)).cookie(owner)));
    }

    @Test
    void storageKeysThatEscapeTheRootAreNeverServed() throws Exception {
        long taskId = submit(owner);
        pipeline.run(taskId);
        // 即便数据库中的 result_url 被改成越界路径，只要与请求路径不一致就查不到；请求路径本身无法表达 ".."
        jdbc.update("UPDATE tasks SET result_url = '/files/results/../../etc/passwd' WHERE id = ?", taskId);
        notFound(mvc.perform(get("/files/results/{t}/{f}", taskId, UUID.randomUUID() + ".png").cookie(owner)));
    }

    @Test
    void onlyGetAndHeadAreSupported() throws Exception {
        long taskId = submit(owner);
        mvc.perform(post(URI.create(assetUrls(taskId).getFirst())).cookie(owner))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---------- 删除账户后链接失效 ----------

    @Test
    void afterAccountDeletionLinksAreDeadForEveryoneButFilesStayOnDisk() throws Exception {
        long taskId = submit(owner);
        pipeline.run(taskId);
        List<String> urls = List.of(assetUrls(taskId).get(0), assetUrls(taskId).get(1), resultUrl(taskId));
        for (String url : urls) {
            mvc.perform(get(URI.create(url)).cookie(owner)).andExpect(status().isOk());
        }

        deleteAccount(ownerId);

        for (String url : urls) {
            unauthenticated(mvc.perform(get(URI.create(url)).cookie(owner)));   // 原 Cookie 已失效
            unauthenticated(mvc.perform(get(URI.create(url))));
            notFound(mvc.perform(get(URI.create(url)).cookie(other)));
            assertThat(storageRoot.resolve(url.substring("/files/".length()))).isRegularFile();   // OQ-26：文件保留
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assets WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(2);
    }

    @Test
    void readingFilesDoesNotModifyTheTask() throws Exception {
        long taskId = submit(owner);
        pipeline.run(taskId);
        var before = jdbc.queryForMap("SELECT * FROM tasks WHERE id = ?", taskId);
        mvc.perform(get(URI.create(resultUrl(taskId))).cookie(owner)).andExpect(status().isOk());
        mvc.perform(get(URI.create(assetUrls(taskId).getFirst())).cookie(owner)).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT * FROM tasks WHERE id = ?", taskId)).isEqualTo(before);
    }

    // ---------- helpers ----------

    private static void unauthenticated(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private static void notFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * 与 database.md §4"删除账户"事务相同的效果（账户接口由 T-012 提供）：
     * 软删除用户 + 废弃进行中的任务 + 删除全部会话。
     */
    private void deleteAccount(long userId) {
        jdbc.update("UPDATE users SET deleted_at = NOW(3) WHERE id = ?", userId);
        taskMapper.abandonActiveByUser(userId);
        jdbc.update("DELETE FROM sessions WHERE user_id = ?", userId);
    }

    private long submit(Cookie cookie) throws Exception {
        String body = mvc.perform(multipart("/api/tasks")
                        .file(new MockMultipartFile("files", "a.jpg", "image/jpeg", jpeg))
                        .file(new MockMultipartFile("files", "b.png", "image/png", png))
                        .param("templateId", POSTCARD)
                        .cookie(cookie))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Long.parseLong(JSON.readTree(body).get("taskId").asString());
    }

    private List<String> assetUrls(long taskId) {
        return jdbc.queryForList("SELECT CONCAT('/files/', storage_key) FROM task_assets WHERE task_id = ? "
                + "ORDER BY slot_index", String.class, taskId);
    }

    private String resultUrl(long taskId) {
        return jdbc.queryForObject("SELECT result_url FROM tasks WHERE id = ?", String.class, taskId);
    }

    private Cookie cookieFor(long userId) {
        return new Cookie(SessionCookies.NAME, sessions.create(userId));
    }

    private long insertUser() {
        String name = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)",
                name, name + "@example.com", HASH);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, name);
    }

    private static byte[] classpathBytes(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** 只有文件头是真实的：服务端只按魔数判定类型，不解码图片。 */
    private static byte[] jpeg(int size) {
        return withHeader(size, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0);
    }

    private static byte[] png(int size) {
        return withHeader(size, (byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G', (byte) 0x0D, (byte) 0x0A,
                (byte) 0x1A, (byte) 0x0A);
    }

    private static byte[] withHeader(int size, byte... header) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 7);
        System.arraycopy(header, 0, b, 0, header.length);
        return b;
    }
}
