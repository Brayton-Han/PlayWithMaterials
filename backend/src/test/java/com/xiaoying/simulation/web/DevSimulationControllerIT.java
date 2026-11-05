package com.xiaoying.simulation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.xiaoying.TestcontainersConfiguration;
import com.xiaoying.auth.security.SessionCookies;
import com.xiaoying.simulation.SimulationConfig;
import com.xiaoying.simulation.SimulationSettings;
import com.xiaoying.task.domain.TaskStage;

import tools.jackson.databind.json.JsonMapper;

/**
 * T-044：{@code GET / PUT /api/dev/simulation}（api.yaml SimulationConfig）。
 * test profile 默认不注册该接口（见 {@link DevSimulationProfileIT}），这里显式打开开关。
 * 所有请求都不带会话：DQ-01 要求该接口匿名可用。
 */
@SpringBootTest(properties = "app.simulation.dev-api-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DevSimulationControllerIT {

    static final String URL = "/api/dev/simulation";
    static final String DEFAULTS = """
            {"assetCheckMs":1500,"generatingMs":3000,"resultOrganizingMs":1500,"failStage":null,"failReason":null}""";
    private static final SimulationConfig DEFAULT_CONFIG = new SimulationConfig(1500, 3000, 1500, null, null);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    SimulationSettings settings;

    @AfterEach
    void restoreDefaults() {
        settings.update(DEFAULT_CONFIG);
    }

    // ---------- GET ----------

    @Test
    void getReturnsTheApplicationYmlDefaultsWithNullsPresent() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(DEFAULTS, JsonCompareMode.STRICT));
    }

    @Test
    void doesNotRequireASessionEvenWithAnInvalidCookie() throws Exception {
        Cookie bogus = new Cookie(SessionCookies.NAME, "not-a-session");
        mvc.perform(get(URL).cookie(bogus)).andExpect(status().isOk());
        mvc.perform(putJson(body(1, 2, 3, "GENERATING", null)).cookie(bogus)).andExpect(status().isOk());
    }

    // ---------- PUT 合法 ----------

    @Test
    void putReplacesTheConfigAndGetReturnsTheSameValue() throws Exception {
        String json = body(1000, 5000, 1000, "GENERATING", null);

        mvc.perform(putJson(json))
                .andExpect(status().isOk())
                .andExpect(content().json(json, JsonCompareMode.STRICT));
        mvc.perform(get(URL)).andExpect(content().json(json, JsonCompareMode.STRICT));
        assertThat(settings.current()).isEqualTo(new SimulationConfig(1000, 5000, 1000, TaskStage.GENERATING, null));
    }

    @Test
    void putIsAFullReplacementNotAMerge() throws Exception {
        mvc.perform(putJson(body(10, 20, 30, "ASSET_CHECK", "自定义原因"))).andExpect(status().isOk());

        String noFailure = body(40, 50, 60, null, null);
        mvc.perform(putJson(noFailure)).andExpect(content().json(noFailure, JsonCompareMode.STRICT));
        mvc.perform(get(URL)).andExpect(content().json(noFailure, JsonCompareMode.STRICT));
    }

    @Test
    void everyStageCanBeTheFailStage() throws Exception {
        for (TaskStage stage : TaskStage.values()) {
            mvc.perform(putJson(body(0, 0, 0, stage.name(), null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.failStage").value(stage.name()));
        }
    }

    @Test
    void boundaryDurationsAreAccepted() throws Exception {
        String json = body(0, SimulationConfig.MAX_STAGE_MS, 0, null, null);
        mvc.perform(putJson(json)).andExpect(status().isOk()).andExpect(content().json(json, JsonCompareMode.STRICT));
    }

    @Test
    void customFailReasonUpTo200CharactersIsKeptVerbatim() throws Exception {
        String reason = "模".repeat(SimulationConfig.MAX_FAIL_REASON_LENGTH);
        mvc.perform(putJson(body(0, 0, 0, "RESULT_ORGANIZING", reason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failReason").value(reason));
        assertThat(settings.current().failReason()).isEqualTo(reason);
    }

    @Test
    void blankFailReasonIsStoredAsNullSoTheDefaultReasonIsUsed() throws Exception {
        mvc.perform(putJson(body(0, 0, 0, "GENERATING", "   ")))
                .andExpect(status().isOk())
                .andExpect(content().json(body(0, 0, 0, "GENERATING", null), JsonCompareMode.STRICT));
    }

    // ---------- PUT 非法 ----------

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("负数", body(-1, 0, 0, null, null), "assetCheckMs", "OUT_OF_RANGE"),
                Arguments.of("超过 60000", body(0, 60001, 0, null, null), "generatingMs", "OUT_OF_RANGE"),
                Arguments.of("超过 60000（结果整理）", body(0, 0, 999_999, null, null), "resultOrganizingMs", "OUT_OF_RANGE"),
                Arguments.of("耗时为 null", body(null, 0, 0, null, null), "assetCheckMs", "REQUIRED"),
                Arguments.of("缺少耗时字段", without("generatingMs"), "generatingMs", "REQUIRED"),
                Arguments.of("缺少 failStage", without("failStage"), "failStage", "REQUIRED"),
                Arguments.of("缺少 failReason", without("failReason"), "failReason", "REQUIRED"),
                Arguments.of("failReason 超过 200", body(0, 0, 0, null, "x".repeat(201)), "failReason", "LENGTH"),
                Arguments.of("未知字段", withExtra("failstage", "GENERATING"), "failstage", "INVALID_VALUE"),
                Arguments.of("非法阶段名", body(0, 0, 0, "PAINTING", null), "failStage", "INVALID_VALUE"),
                Arguments.of("小写阶段名", body(0, 0, 0, "generating", null), "failStage", "INVALID_VALUE"),
                Arguments.of("阶段用数字", raw("\"failStage\":1"), "failStage", "INVALID_VALUE"),
                Arguments.of("阶段是空字符串", body(0, 0, 0, "", null), "failStage", "INVALID_VALUE"),
                Arguments.of("耗时是字符串", raw("\"assetCheckMs\":\"1000\""), "assetCheckMs", "FORMAT"),
                Arguments.of("耗时是小数", raw("\"generatingMs\":1.5"), "generatingMs", "FORMAT"),
                Arguments.of("耗时是布尔", raw("\"resultOrganizingMs\":true"), "resultOrganizingMs", "FORMAT"),
                Arguments.of("耗时超出 int", body(99_999_999_999L, 0, 0, null, null), "assetCheckMs", "OUT_OF_RANGE"),
                Arguments.of("failReason 不是字符串", raw("\"failReason\":123"), "failReason", "FORMAT"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void invalidFieldIsRejectedWithAFieldErrorAndTheConfigIsUnchanged(String name, String json,
                                                                      String field, String code) throws Exception {
        mvc.perform(putJson(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value(field))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value(code))
                .andExpect(jsonPath("$.details.fieldErrors[0].message").isNotEmpty());
        assertUnchanged();
    }

    static Stream<Arguments> unreadableBodies() {
        return Stream.of(
                Arguments.of("JSON 格式错误", "{\"assetCheckMs\":"),
                Arguments.of("不是对象", "[]"),
                Arguments.of("空请求体", ""),
                Arguments.of("请求体是 null", "null"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableBodies")
    void unreadableBodyIsRejectedAndTheConfigIsUnchanged(String name, String json) throws Exception {
        mvc.perform(putJson(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertUnchanged();
    }

    @Test
    void allFieldErrorsAreReportedTogether() throws Exception {
        Map<String, Object> map = fields(-5, 70_000, null, null, "x".repeat(201));
        map.remove("failStage");
        map.put("extra", 1);

        mvc.perform(putJson(JSON.writeValueAsString(map)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fieldErrors", hasSize(6)))
                .andExpect(jsonPath("$.details.fieldErrors[*].field").value(containsInAnyOrder(
                        "assetCheckMs", "generatingMs", "resultOrganizingMs", "failReason", "failStage", "extra")));
        assertUnchanged();
    }

    @Test
    void nonJsonContentTypeIs415() throws Exception {
        mvc.perform(put(URL).contentType(MediaType.TEXT_PLAIN).content(DEFAULTS))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        assertUnchanged();
    }

    @Test
    void otherMethodsAre405() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(DEFAULTS))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(delete(URL)).andExpect(status().isMethodNotAllowed());
        assertUnchanged();
    }

    // ---------- helpers ----------

    private void assertUnchanged() throws Exception {
        assertThat(settings.current()).isEqualTo(DEFAULT_CONFIG);
        mvc.perform(get(URL)).andExpect(content().json(DEFAULTS, JsonCompareMode.STRICT));
    }

    private static MockHttpServletRequestBuilder putJson(String json) {
        return put(URL).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    static String body(Number assetCheckMs, Number generatingMs, Number resultOrganizingMs,
                       String failStage, String failReason) {
        return JSON.writeValueAsString(fields(assetCheckMs, generatingMs, resultOrganizingMs, failStage, failReason));
    }

    private static Map<String, Object> fields(Number assetCheckMs, Number generatingMs, Number resultOrganizingMs,
                                              String failStage, String failReason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("assetCheckMs", assetCheckMs);
        map.put("generatingMs", generatingMs);
        map.put("resultOrganizingMs", resultOrganizingMs);
        map.put("failStage", failStage);
        map.put("failReason", failReason);
        return map;
    }

    private static String without(String field) {
        Map<String, Object> map = fields(0, 0, 0, null, null);
        map.remove(field);
        return JSON.writeValueAsString(map);
    }

    /** 默认的合法请求体，其中一个字段替换为原始 JSON 片段（{@code "name":value}）。 */
    private static String raw(String fragment) {
        String name = fragment.substring(1, fragment.indexOf('"', 1));
        Map<String, Object> map = fields(0, 0, 0, null, null);
        map.remove(name);
        String rest = JSON.writeValueAsString(map);
        return "{" + fragment + "," + rest.substring(1);
    }

    private static String withExtra(String field, Object value) {
        Map<String, Object> map = fields(0, 0, 0, null, null);
        map.put(field, value);
        return JSON.writeValueAsString(map);
    }
}
