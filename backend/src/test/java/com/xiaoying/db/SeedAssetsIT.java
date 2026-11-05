package com.xiaoying.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.xiaoying.TestcontainersConfiguration;

/**
 * T-021：V2 种子中每个模板引用的封面（cover_url → classpath:/seed/）和预置结果图
 * （sample_result_path）都随 jar 打包，且文件头与扩展名一致。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SeedAssetsIT {

    private static final String SEED_URL_PREFIX = "/files/seed/";
    private static final String JPEG = "ffd8ff";
    private static final String PNG = "89504e470d0a1a0a";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyTemplateCoverAndResultExistsOnClasspathWithMatchingMagicBytes() throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, cover_url, sample_result_path FROM templates");
        assertThat(rows).isNotEmpty();

        for (Map<String, Object> row : rows) {
            String cover = (String) row.get("cover_url");
            assertThat(cover).as("cover_url of %s", row.get("id")).startsWith(SEED_URL_PREFIX);
            assertImage("seed/" + cover.substring(SEED_URL_PREFIX.length()));
            assertImage((String) row.get("sample_result_path"));
        }
    }

    private static void assertImage(String classpathPath) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpathPath);
        assertThat(resource.exists()).as("classpath:/%s exists", classpathPath).isTrue();

        String expected = classpathPath.endsWith(".png") ? PNG
                : classpathPath.endsWith(".jpg") || classpathPath.endsWith(".jpeg") ? JPEG
                : null;
        assertThat(expected).as("supported extension: %s", classpathPath).isNotNull();

        try (InputStream in = resource.getInputStream()) {
            byte[] head = in.readNBytes(expected.length() / 2);
            assertThat(HexFormat.of().formatHex(head)).as("magic bytes of %s", classpathPath).isEqualTo(expected);
        }
    }
}
