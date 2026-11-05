package com.xiaoying.simulation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import com.xiaoying.TestcontainersConfiguration;
import com.xiaoying.simulation.SimulationConfig;
import com.xiaoying.simulation.SimulationSettings;

/**
 * T-044 完成定义"prod profile 下接口返回 404"：用真实的 profile 配置文件（不手动设置开关）启动应用，
 * 确认 {@code /api/dev/simulation} 只在 dev / demo 下注册。数据源由 Testcontainers 提供；
 * prod 的 {@code ${DB_URL}} 等占位符给假值即可（连接信息被 {@code @ServiceConnection} 覆盖）。
 */
class DevSimulationProfileIT {

    private static final String URL = DevSimulationControllerIT.URL;
    private static final String GENERATING = DevSimulationControllerIT.body(0, 0, 0, "GENERATING", null);

    @Nested
    @SpringBootTest(properties = "app.storage.root=${java.io.tmpdir}/xiaoying-profile-it-demo")
    @AutoConfigureMockMvc
    @ActiveProfiles("demo")
    @Import(TestcontainersConfiguration.class)
    class DemoProfile {

        @Autowired
        MockMvc mvc;

        @Autowired
        SimulationSettings settings;

        @Test
        void endpointIsRegisteredAndAnonymous() throws Exception {
            mvc.perform(get(URL))
                    .andExpect(status().isOk())
                    .andExpect(content().json(DevSimulationControllerIT.DEFAULTS, JsonCompareMode.STRICT));
            mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(GENERATING))
                    .andExpect(status().isOk())
                    .andExpect(content().json(GENERATING, JsonCompareMode.STRICT));
            settings.update(new SimulationConfig(1500, 3000, 1500, null, null));
        }
    }

    @Nested
    @SpringBootTest(properties = "app.storage.root=${java.io.tmpdir}/xiaoying-profile-it-dev")
    @AutoConfigureMockMvc
    @ActiveProfiles("dev")
    @Import(TestcontainersConfiguration.class)
    class DevProfile {

        @Autowired
        MockMvc mvc;

        @Test
        void endpointIsRegistered() throws Exception {
            mvc.perform(get(URL)).andExpect(status().isOk());
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "DB_URL=jdbc:mysql://unused:3306/unused",
            "DB_USERNAME=unused",
            "DB_PASSWORD=unused",
            "app.storage.root=${java.io.tmpdir}/xiaoying-profile-it-prod"})
    @AutoConfigureMockMvc
    @ActiveProfiles("prod")
    @Import(TestcontainersConfiguration.class)
    class ProdProfile {

        @Autowired
        MockMvc mvc;

        @Autowired
        ApplicationContext context;

        @Test
        void endpointDoesNotExist() throws Exception {
            assertThat(context.getBeanNamesForType(DevSimulationController.class)).isEmpty();
            mvc.perform(get(URL))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(GENERATING))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Import(TestcontainersConfiguration.class)
    class TestProfileWithoutSwitch {

        @Autowired
        MockMvc mvc;

        @Test
        void endpointDoesNotExist() throws Exception {
            mvc.perform(get(URL))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }
}
