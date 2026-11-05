package com.xiaoying.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.unit.DataSize;

import com.xiaoying.TestcontainersConfiguration;

/**
 * T-030 / OQ-18：全局 multipart 上限能容纳每个模板的一次完整提交（张数 × 单张上限），
 * 单张全局上限不小于任何模板的单张上限；存储根目录来自 {@code app.storage.root}。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MultipartLimitsIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FileStorageService storage;

    @Value("${spring.servlet.multipart.max-file-size}")
    DataSize maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size}")
    DataSize maxRequestSize;

    @Value("${app.storage.root}")
    Path configuredRoot;

    @Test
    void configuredLimitsMatchOq18Defaults() {
        assertThat(maxFileSize).isEqualTo(DataSize.ofMegabytes(5));
        assertThat(maxRequestSize).isEqualTo(DataSize.ofMegabytes(20));
    }

    @Test
    void globalLimitsCoverEveryTemplatesFullSubmission() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, required_image_count, max_file_size_bytes FROM templates");
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> r : rows) {
            long count = ((Number) r.get("required_image_count")).longValue();
            long perFile = ((Number) r.get("max_file_size_bytes")).longValue();
            assertThat(perFile).as("max-file-size vs template %s", r.get("id"))
                    .isLessThanOrEqualTo(maxFileSize.toBytes());
            assertThat(count * perFile).as("max-request-size vs template %s", r.get("id"))
                    .isLessThan(maxRequestSize.toBytes()); // 严格小于：留出 multipart 边界与 templateId 字段的余量
        }
    }

    @Test
    void storageRootComesFromConfiguration() {
        assertThat(storage.root()).isEqualTo(configuredRoot.toAbsolutePath().normalize());
    }
}
