package com.xiaoying.task.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.xiaoying.TestcontainersConfiguration;
import com.xiaoying.auth.security.SessionCookies;
import com.xiaoying.auth.service.SessionService;
import com.xiaoying.task.mapper.TaskAssetMapper;
import com.xiaoying.task.pipeline.TaskDispatcher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-041 完成定义（backlog）与 implementation-plan §3 中 TaskSubmissionIT 的条目（MockMultipartFile，执行器替换为 mock）：
 * 成功 → 202 + Location + PENDING，事务提交、文件 commit 之后才派发；模板不存在 404；张数不符 400；
 * 类型 / 大小 / 空文件 400 带正确 slotIndex；未登录 401；派发被拒绝 503 QUEUE_FULL；数据库写入失败 500。
 * <b>每个失败用例都断言</b>没有任务 / 素材记录、存储目录（tmp 与 uploads）没有新文件、执行器未被调用（或已撤销）。
 * 请求体超过全局 multipart 上限（413）需要真实 Tomcat，见 {@link TaskSubmissionHttpIT}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TaskSubmissionIT {

    private static final String HASH = "$2a$10$BpxBLDiVUYoM.u0vpiOWEuR1mpe3GVxuFy0hTByNsvFr8zfdU1Ts6";
    private static final String ISO_MILLIS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";
    private static final String UPLOAD_URL = "^/files/uploads/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.%s$";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** jpeg / png，2 张，5 MB。 */
    private static final String POSTCARD = "demo-postcard";
    /** jpeg / png / webp，1 张，5 MB。 */
    private static final String POSTER = "creative-poster";
    /** 只接受 png，1 张，1 MB。 */
    private static final String ID_PHOTO = "portrait-id-photo";
    /** jpeg / png / webp，3 张，5 MB。 */
    private static final String COLLAGE = "festival-collage";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @MockitoBean
    TaskDispatcher dispatcher;

    @MockitoSpyBean
    TaskAssetMapper assetMapper;

    @Value("${app.storage.root}")
    Path storageRoot;

    long user;
    Cookie cookie;
    Set<Path> filesBefore;
    long assetRowsBefore;

    @BeforeEach
    void setUp() throws IOException {
        user = insertUser();
        cookie = new Cookie(SessionCookies.NAME, sessions.create(user));
        filesBefore = storedFiles();
        assetRowsBefore = count("SELECT COUNT(*) FROM task_assets");
    }

    // ---------- 成功 ----------

    @Test
    void acceptedSubmissionReturns202PendingTaskAndDispatchesAfterCommit() throws Exception {
        byte[] jpeg = jpeg(2048);
        byte[] png = png(1024);
        AtomicReference<String> statusSeenByExecutor = new AtomicReference<>();
        AtomicReference<Long> committedFilesSeenByExecutor = new AtomicReference<>();
        doAnswer(inv -> {
            long id = inv.getArgument(0);
            // 执行器被调用时：事务已提交（其他连接可见）、文件已在正式目录
            statusSeenByExecutor.set(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, id));
            committedFilesSeenByExecutor.set(newFiles().stream().filter(p -> p.startsWith(storageRoot.resolve("uploads")))
                    .count());
            return null;
        }).when(dispatcher).dispatch(anyLong());

        String body = submit(cookie, POSTCARD, file("photo.jpg", "image/jpeg", jpeg), file("pic.png", "image/png", png))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", matchesPattern("^/api/tasks/\\d+$")))
                .andExpect(jsonPath("$.taskId").value(matchesPattern("^\\d+$")))
                .andExpect(jsonPath("$.templateId").value(POSTCARD))
                .andExpect(jsonPath("$.templateTitle").value("旅行明信片"))
                .andExpect(jsonPath("$.templateCoverUrl").value("/files/seed/covers/postcard.jpg"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.stage").value(nullValue()))
                .andExpect(jsonPath("$.failedStage").value(nullValue()))
                .andExpect(jsonPath("$.failureCode").value(nullValue()))
                .andExpect(jsonPath("$.failureReason").value(nullValue()))
                .andExpect(jsonPath("$.resultUrl").value(nullValue()))
                .andExpect(jsonPath("$.startedAt").value(nullValue()))
                .andExpect(jsonPath("$.finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern(ISO_MILLIS)))
                .andExpect(jsonPath("$.updatedAt").value(matchesPattern(ISO_MILLIS)))
                .andExpect(jsonPath("$.inputAssets", hasSize(2)))
                .andExpect(jsonPath("$.inputAssets[*].slotIndex").value(contains(0, 1)))
                .andExpect(jsonPath("$.inputAssets[0].url").value(matchesPattern(UPLOAD_URL.formatted("jpg"))))
                .andExpect(jsonPath("$.inputAssets[1].url").value(matchesPattern(UPLOAD_URL.formatted("png"))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode dto = JSON.readTree(body);
        long taskId = Long.parseLong(dto.get("taskId").asString());
        assertThat(dto.propertyNames()).doesNotContain("userId", "simConfig", "storageKey");

        verify(dispatcher, times(1)).dispatch(taskId);
        assertThat(statusSeenByExecutor.get()).isEqualTo("PENDING");
        assertThat(committedFilesSeenByExecutor.get()).isEqualTo(2L);

        // 数据库：任务 PENDING，模拟配置按默认值快照 [OQ-10]
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_id, template_id, status, stage, CAST(sim_config AS CHAR) AS sim FROM tasks WHERE id = ?",
                taskId);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(user);
        assertThat(row.get("template_id")).isEqualTo(POSTCARD);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("stage")).isNull();
        JsonNode sim = JSON.readTree((String) row.get("sim"));
        assertThat(sim.get("assetCheckMs").asLong()).isEqualTo(1500);
        assertThat(sim.get("generatingMs").asLong()).isEqualTo(3000);
        assertThat(sim.get("resultOrganizingMs").asLong()).isEqualTo(1500);
        assertThat(sim.get("failStage").isNull()).isTrue();
        assertThat(sim.get("failReason").isNull()).isTrue();

        // 素材：mime_type 来自魔数检测，storage_key 与 URL 一致，原始文件名只做记录
        List<Map<String, Object>> assets = jdbc.queryForList(
                "SELECT slot_index, storage_key, original_name, mime_type, size_bytes FROM task_assets "
                        + "WHERE task_id = ? ORDER BY slot_index", taskId);
        assertThat(assets).hasSize(2);
        assertThat(assets.get(0)).containsEntry("original_name", "photo.jpg").containsEntry("mime_type", "image/jpeg");
        assertThat(((Number) assets.get(0).get("size_bytes")).longValue()).isEqualTo(jpeg.length);
        assertThat(assets.get(1)).containsEntry("original_name", "pic.png").containsEntry("mime_type", "image/png");
        assertThat(dto.get("inputAssets").get(0).get("url").asString())
                .isEqualTo("/files/" + assets.get(0).get("storage_key"));

        // 文件：只在正式目录、内容一致；暂存目录没有残留
        Path first = storageRoot.resolve((String) assets.get(0).get("storage_key"));
        Path second = storageRoot.resolve((String) assets.get(1).get("storage_key"));
        assertThat(Files.readAllBytes(first)).isEqualTo(jpeg);
        assertThat(Files.readAllBytes(second)).isEqualTo(png);
        assertThat(newFiles()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void acceptsEveryAcceptedTypeAndTheExactSizeLimit() throws Exception {
        submit(cookie, POSTER, file("a.webp", "image/webp", webp(512))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inputAssets[0].url").value(matchesPattern(UPLOAD_URL.formatted("webp"))));
        // 恰好等于模板上限（1 MB）可以通过
        submit(cookie, ID_PHOTO, file("me.png", "image/png", png(1024 * 1024))).andExpect(status().isAccepted());
        submit(cookie, COLLAGE, file("1.jpg", "image/jpeg", jpeg(100)), file("2.png", "image/png", png(100)),
                file("3.webp", "image/webp", webp(100))).andExpect(status().isAccepted());

        assertThat(count("SELECT COUNT(*) FROM tasks WHERE user_id = ?", user)).isEqualTo(3);
        verify(dispatcher, times(3)).dispatch(anyLong());
    }

    @Test
    void consecutiveSubmissionsCreateIndependentTasks() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String body = submit(cookie, POSTER, file("a.jpg", "image/jpeg", jpeg(100)))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            ids.add(JSON.readTree(body).get("taskId").asString());
        }
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(newFiles()).hasSize(3);
    }

    @Test
    void storedExtensionAndUrlComeFromMagicBytesNotFromTheClientFileName() throws Exception {
        // 客户端文件名是 ../../evil.jpg：不影响存储路径，只按原样记录
        submit(cookie, POSTER, file("../../evil.jpg", "image/jpeg", jpeg(100)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inputAssets[0].url").value(matchesPattern(UPLOAD_URL.formatted("jpg"))));
        assertThat(newFiles()).allSatisfy(p -> assertThat(p.normalize()).startsWith(storageRoot.resolve("uploads")));
    }

    // ---------- 未登录 ----------

    @Test
    void anonymousOrInvalidSessionIs401AndCreatesNothing() throws Exception {
        mvc.perform(multipart("/api/tasks").file(file("a.jpg", "image/jpeg", jpeg(100))).param("templateId", POSTER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(multipart("/api/tasks").file(file("a.jpg", "image/jpeg", jpeg(100))).param("templateId", POSTER)
                        .cookie(new Cookie(SessionCookies.NAME, "not-a-real-token")))
                .andExpect(status().isUnauthorized());
        // 非 multipart 的匿名请求同样是 401，而不是 415
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        assertNothingCreated();
    }

    // ---------- 请求格式 ----------

    @Test
    void missingOrOverlongTemplateIdIs400ValidationFailed() throws Exception {
        mvc.perform(multipart("/api/tasks").file(file("a.jpg", "image/jpeg", jpeg(100))).cookie(cookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("templateId"))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("REQUIRED"));
        submit(cookie, "   ", file("a.jpg", "image/jpeg", jpeg(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("REQUIRED"));
        submit(cookie, "x".repeat(65), file("a.jpg", "image/jpeg", jpeg(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("templateId"))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("LENGTH"));
        assertNothingCreated();
    }

    @Test
    void nonMultipartRequestIs415() throws Exception {
        mvc.perform(post("/api/tasks").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"creative-poster\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(post("/api/tasks").cookie(cookie).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("templateId", POSTER))
                .andExpect(status().isUnsupportedMediaType());
        assertNothingCreated();
    }

    // ---------- 模板与数量 ----------

    @Test
    void unknownTemplateIs404() throws Exception {
        submit(cookie, "no-such-template", file("a.jpg", "image/jpeg", jpeg(100)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("模板不存在"));
        assertNothingCreated();
    }

    @Test
    void wrongImageCountIs400WithExpectedAndActual() throws Exception {
        submit(cookie, POSTCARD, file("a.jpg", "image/jpeg", jpeg(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_COUNT_MISMATCH"))
                .andExpect(jsonPath("$.message").value("该模板需要上传 2 张图片"))
                .andExpect(jsonPath("$.details.expected").value(2))
                .andExpect(jsonPath("$.details.actual").value(1));
        submit(cookie, POSTCARD, file("a.jpg", "image/jpeg", jpeg(100)), file("b.jpg", "image/jpeg", jpeg(100)),
                file("c.jpg", "image/jpeg", jpeg(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.actual").value(3));
        // 完全没有 files 部分
        submit(cookie, POSTCARD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_COUNT_MISMATCH"))
                .andExpect(jsonPath("$.details.actual").value(0));
        assertNothingCreated();
    }

    @Test
    void templateIsCheckedBeforeImageCount() throws Exception {
        submit(cookie, "no-such-template").andExpect(status().isNotFound());
        assertNothingCreated();
    }

    // ---------- 逐个文件校验（slotIndex） ----------

    @Test
    void disguisedNonImageIsRejectedAtItsSlot() throws Exception {
        byte[] text = "this is plain text pretending to be a jpeg".getBytes(StandardCharsets.UTF_8);
        submit(cookie, POSTCARD, file("ok.jpg", "image/jpeg", jpeg(100)), file("fake.jpg", "image/jpeg", text))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSET_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("部分图片不符合要求"))
                .andExpect(jsonPath("$.details.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("files"))
                .andExpect(jsonPath("$.details.fieldErrors[0].slotIndex").value(1))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("FILE_TYPE_NOT_ACCEPTED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].message").value("第 2 张图片格式不支持，仅支持 JPEG、PNG"));
        assertNothingCreated();
    }

    @Test
    void realImageOfATypeTheTemplateDoesNotAcceptIsRejected() throws Exception {
        // webp 是真实图片，但 demo-postcard 只接受 jpeg / png
        submit(cookie, POSTCARD, file("a.webp", "image/webp", webp(100)), file("b.png", "image/png", png(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fieldErrors[0].slotIndex").value(0))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("FILE_TYPE_NOT_ACCEPTED"));
        // 只接受 png 的模板：真实 jpeg 声明成 image/png 也不行（按魔数判定）
        submit(cookie, ID_PHOTO, file("me.png", "image/png", jpeg(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("FILE_TYPE_NOT_ACCEPTED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].message").value("第 1 张图片格式不支持，仅支持 PNG"));
        assertNothingCreated();
    }

    @Test
    void declaredContentTypeMustAlsoBeAccepted() throws Exception {
        // OQ-17 临时默认：Content-Type 与魔数双重校验；内容是真实 png，但声明为 octet-stream / 缺失
        submit(cookie, POSTER, file("a.png", "application/octet-stream", png(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("FILE_TYPE_NOT_ACCEPTED"));
        submit(cookie, POSTER, file("a.png", null, png(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("FILE_TYPE_NOT_ACCEPTED"));
        assertNothingCreated();
    }

    @Test
    void fileOverTheTemplateLimitIsRejected() throws Exception {
        submit(cookie, ID_PHOTO, file("big.png", "image/png", png(1024 * 1024 + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSET_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].slotIndex").value(0))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.details.fieldErrors[0].message").value("第 1 张图片超过 1 MB 上限"));
        assertNothingCreated();
    }

    @Test
    void emptyFileIsRejected() throws Exception {
        submit(cookie, POSTER, file("empty.jpg", "image/jpeg", new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fieldErrors[0].slotIndex").value(0))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("FILE_EMPTY"));
        assertNothingCreated();
    }

    @Test
    void everyBadSlotIsReportedAndGoodSlotsAreNotWritten() throws Exception {
        submit(cookie, COLLAGE,
                file("ok.jpg", "image/jpeg", jpeg(100)),
                file("empty.png", "image/png", new byte[0]),
                file("fake.jpg", "image/jpeg", "nope".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fieldErrors[*].slotIndex").value(contains(1, 2)))
                .andExpect(jsonPath("$.details.fieldErrors[*].code").value(contains("FILE_EMPTY", "FILE_TYPE_NOT_ACCEPTED")));
        assertNothingCreated();
    }

    // ---------- 受理之后的失败：补偿 ----------

    @Test
    void executorRejectionWithdrawsTaskAndFilesAndReturns503() throws Exception {
        doThrow(new TaskRejectedException("queue full")).when(dispatcher).dispatch(anyLong());

        submit(cookie, POSTCARD, file("a.jpg", "image/jpeg", jpeg(100)), file("b.png", "image/png", png(100)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("QUEUE_FULL"))
                .andExpect(jsonPath("$.message").value("当前制作任务较多，请稍后再试"));

        verify(dispatcher, times(1)).dispatch(anyLong());
        assertNoRowsOrFiles();
    }

    @Test
    void databaseFailureRollsBackTheWholeTransactionAndDeletesStagedFiles() throws Exception {
        // 第一条素材写入成功，第二条失败：整个事务（含任务行与第一条素材）回滚
        // （spy 包着 MyBatis 接口代理：只对 slot 1 打桩，slot 0 仍走真实 Mapper）
        doThrow(new DataIntegrityViolationException("simulated failure"))
                .when(assetMapper).insert(argThat(a -> a.getSlotIndex() == 1));

        submit(cookie, POSTCARD, file("a.jpg", "image/jpeg", jpeg(100)), file("b.png", "image/png", png(100)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        verify(assetMapper, times(2)).insert(any());
        verify(dispatcher, never()).dispatch(anyLong());
        assertNoRowsOrFiles();
    }

    // ---------- helpers ----------

    private ResultActions submit(Cookie c, String templateId, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/api/tasks");
        for (MockMultipartFile f : files) {
            req.file(f);
        }
        return mvc.perform(req.param("templateId", templateId).cookie(c));
    }

    private void assertNothingCreated() throws IOException {
        verify(dispatcher, never()).dispatch(anyLong());
        assertNoRowsOrFiles();
    }

    private void assertNoRowsOrFiles() throws IOException {
        assertThat(count("SELECT COUNT(*) FROM tasks WHERE user_id = ?", user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM task_assets")).isEqualTo(assetRowsBefore);
        assertThat(newFiles()).isEmpty();
    }

    private Set<Path> newFiles() throws IOException {
        Set<Path> now = storedFiles();
        now.removeAll(filesBefore);
        return now;
    }

    private Set<Path> storedFiles() throws IOException {
        if (!Files.exists(storageRoot)) {
            return new java.util.HashSet<>();
        }
        try (Stream<Path> s = Files.walk(storageRoot)) {
            return s.filter(Files::isRegularFile).collect(Collectors.toCollection(java.util.HashSet::new));
        }
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long insertUser() {
        String name = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)",
                name, name + "@example.com", HASH);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, name);
    }

    static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("files", name, contentType, content);
    }

    /** 只有文件头是真实的：服务端只按魔数判定类型，不解码图片。 */
    static byte[] jpeg(int size) {
        return withHeader(size, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0);
    }

    static byte[] png(int size) {
        return withHeader(size, (byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G', (byte) 0x0D, (byte) 0x0A,
                (byte) 0x1A, (byte) 0x0A);
    }

    static byte[] webp(int size) {
        return withHeader(size, (byte) 'R', (byte) 'I', (byte) 'F', (byte) 'F', (byte) 0, (byte) 0, (byte) 0,
                (byte) 0, (byte) 'W', (byte) 'E', (byte) 'B', (byte) 'P');
    }

    private static byte[] withHeader(int size, byte... header) {
        byte[] data = new byte[Math.max(size, header.length)];
        Arrays.fill(data, (byte) 0x42);
        System.arraycopy(header, 0, data, 0, header.length);
        return data;
    }
}
