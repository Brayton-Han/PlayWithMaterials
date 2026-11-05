package com.xiaoying.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.xiaoying.TestcontainersConfiguration;

/**
 * T-020 完成定义（backlog）与 implementation-plan §3 中 CatalogControllerIT 的条目：
 * 全部 / 按分类 / 不存在的分类 / 详情字段与 api.yaml 一致且不含 sample_result_path / 不存在 → 404；
 * /files/seed/** 可访问且不能路径穿越。数据来自 V2 种子，测试只读。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CatalogControllerIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    // ---------- GET /api/categories ----------

    @Test
    void categoriesAreSortedAndMatchContractShape() throws Exception {
        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"items":[
                          {"categoryId":"creative","name":"创意图片","sortOrder":10},
                          {"categoryId":"portrait","name":"人像写真","sortOrder":20},
                          {"categoryId":"festival","name":"节日贺卡","sortOrder":30}
                        ]}""", JsonCompareMode.STRICT));
    }

    // ---------- GET /api/templates ----------

    @Test
    void listAllTemplatesInSeedOrderWithCardFieldsOnly() throws Exception {
        mvc.perform(get("/api/templates"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items":[
                          {"templateId":"demo-postcard","title":"旅行明信片","categoryId":"creative","coverUrl":"/files/seed/covers/postcard.jpg"},
                          {"templateId":"creative-poster","title":"创意海报","categoryId":"creative","coverUrl":"/files/seed/covers/poster.jpg"},
                          {"templateId":"portrait-id-photo","title":"证件照","categoryId":"portrait","coverUrl":"/files/seed/covers/id-photo.jpg"},
                          {"templateId":"festival-collage","title":"节日拼图贺卡","categoryId":"festival","coverUrl":"/files/seed/covers/collage.jpg"}
                        ]}""", JsonCompareMode.STRICT));
    }

    @Test
    void listAllCoversEveryTemplateInDatabase() throws Exception {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM templates", Integer.class);
        mvc.perform(get("/api/templates")).andExpect(jsonPath("$.items", hasSize(count)));
    }

    @Test
    void blankCategoryIdMeansAll() throws Exception {
        mvc.perform(get("/api/templates").param("categoryId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(4)));
    }

    @Test
    void filterByCategoryReturnsOnlyThatCategorySorted() throws Exception {
        mvc.perform(get("/api/templates").param("categoryId", "creative"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].templateId").value("demo-postcard"))
                .andExpect(jsonPath("$.items[1].templateId").value("creative-poster"))
                .andExpect(jsonPath("$.items[*].categoryId", everyItem(is("creative"))));

        mvc.perform(get("/api/templates").param("categoryId", "festival"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].templateId").value("festival-collage"));
    }

    @Test
    void unknownCategoryReturnsEmptyList() throws Exception {
        mvc.perform(get("/api/templates").param("categoryId", "no-such-category"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"items\":[]}", JsonCompareMode.STRICT));
    }

    @Test
    void tooLongCategoryIdIs400() throws Exception {
        mvc.perform(get("/api/templates").param("categoryId", "x".repeat(33)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("categoryId"))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("LENGTH"));
    }

    // ---------- GET /api/templates/{templateId} ----------

    @Test
    void templateDetailMatchesContractAndHidesSampleResultPath() throws Exception {
        MvcResult result = mvc.perform(get("/api/templates/demo-postcard"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"templateId":"demo-postcard","title":"旅行明信片",
                         "description":"上传图片，模拟生成一张明信片",
                         "categoryId":"creative","categoryName":"创意图片",
                         "coverUrl":"/files/seed/covers/postcard.jpg",
                         "requiredImageCount":2,
                         "acceptedMimeTypes":["image/jpeg","image/png"],
                         "maxFileSizeBytes":5242880}""", JsonCompareMode.STRICT))
                .andReturn();
        // 预置结果图路径不对外暴露（database.md §2.4）
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("seed/results").doesNotContain("sample");
    }

    @Test
    void templateDetailCoversSeedVariants() throws Exception {
        // 只接受 PNG、上限 1 MB
        mvc.perform(get("/api/templates/portrait-id-photo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredImageCount").value(1))
                .andExpect(jsonPath("$.acceptedMimeTypes", contains("image/png")))
                .andExpect(jsonPath("$.maxFileSizeBytes").value(1048576))
                .andExpect(jsonPath("$.categoryName").value("人像写真"));
        mvc.perform(get("/api/templates/festival-collage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredImageCount").value(3))
                .andExpect(jsonPath("$.acceptedMimeTypes", contains("image/jpeg", "image/png", "image/webp")));
    }

    @Test
    void unknownTemplateIs404TemplateNotFound() throws Exception {
        mvc.perform(get("/api/templates/no-such-template"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":\"TEMPLATE_NOT_FOUND\",\"message\":\"模板不存在\"}",
                        JsonCompareMode.STRICT));
        mvc.perform(get("/api/templates/" + "x".repeat(65)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));
    }

    // ---------- 访问控制 ----------

    @Test
    void catalogIsAnonymousAndIgnoresInvalidSession() throws Exception {
        // 匿名 200（T-010 遗留复核项）；带无效 / 过期 Cookie 也不被拦截
        Cookie bogus = new Cookie("sid", "not-a-real-session-token");
        for (String url : List.of("/api/categories", "/api/templates", "/api/templates/demo-postcard")) {
            mvc.perform(get(url)).andExpect(status().isOk());
            mvc.perform(get(url).cookie(bogus)).andExpect(status().isOk());
        }
    }

    @Test
    void catalogIsReadOnly() throws Exception {
        // 只读：写方法没有处理器，路由阶段即返回 405（先于鉴权拦截器），不暴露任何数据
        for (String url : List.of("/api/templates", "/api/categories", "/api/templates/demo-postcard")) {
            mvc.perform(post(url))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        }
    }

    // ---------- /files/seed/** ----------

    @Test
    void seedCoversAreServedPublicly() throws Exception {
        List<String> coverUrls = jdbc.queryForList("SELECT cover_url FROM templates", String.class);
        for (String url : coverUrls) {
            mvc.perform(get(url))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                    .andExpect(header().string("Cache-Control", containsString("max-age")));
        }
    }

    @Test
    void seedSampleResultsAreServedAsPng() throws Exception {
        mvc.perform(get("/files/seed/results/postcard.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void missingSeedFileIsUnified404() throws Exception {
        mvc.perform(get("/files/seed/covers/nope.jpg"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void seedDirectoryIsNotListed() throws Exception {
        mvc.perform(get("/files/seed/")).andExpect(status().isNotFound());
        mvc.perform(get("/files/seed/covers/")).andExpect(status().isNotFound());
    }

    @Test
    void pathTraversalCannotReachOtherClasspathResources() throws Exception {
        for (String url : List.of(
                "/files/seed/../application.yml",
                "/files/seed/%2e%2e/application.yml",
                "/files/seed/..%2fapplication.yml",
                "/files/seed/%2e%2e%2fapplication.yml",
                "/files/../application.yml",
                "/files/seed/../db/migration/V2__seed_data.sql",
                "/files/application.yml",
                "/application.yml")) {
            mvc.perform(get(url))
                    .andExpect(status().is(not(200)))
                    .andExpect(content().string(not(containsString("datasource"))))
                    .andExpect(content().string(not(containsString("INSERT"))));
        }
    }
}
