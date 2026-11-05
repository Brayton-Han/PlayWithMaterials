package com.xiaoying.common.error;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 每类异常的 HTTP 状态和 {code,message[,details]} 结构与 api.yaml 一致；500 响应不含堆栈。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler(DataSize.ofMegabytes(5), DataSize.ofMegabytes(20)))
                .setValidator(validator)
                .build();
    }

    @Test
    void apiExceptionUsesItsCodeStatusAndDetails() throws Exception {
        mvc.perform(get("/test/api-exception"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_COUNT_MISMATCH"))
                .andExpect(jsonPath("$.message").value("需要 2 张图片"))
                .andExpect(jsonPath("$.details.expected").value(2))
                .andExpect(jsonPath("$.details.actual").value(1));
    }

    @Test
    void apiExceptionWithoutDetailsOmitsDetailsField() throws Exception {
        mvc.perform(get("/test/not-found-business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("任务不存在"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void bodyValidationReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a!\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("请求参数不合法"))
                .andExpect(jsonPath("$.details.fieldErrors[?(@.field=='username' && @.code=='PATTERN')]").exists())
                .andExpect(jsonPath("$.details.fieldErrors[?(@.field=='password' && @.code=='REQUIRED')]").exists());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void pathVariableTypeMismatchReturns400() throws Exception {
        mvc.perform(get("/test/tasks/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("taskId"))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("FORMAT"));
    }

    @Test
    void maxUploadSizeExceededReturns413WithLimits() throws Exception {
        mvc.perform(get("/test/too-large"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value("上传内容过大，单张图片不超过 5 MB，总大小不超过 20 MB"))
                .andExpect(jsonPath("$.details.maxFileSizeBytes").value(5 * 1024 * 1024))
                .andExpect(jsonPath("$.details.maxRequestSizeBytes").value(20 * 1024 * 1024));
    }

    @Test
    void noResourceFoundReturns404NotFound() throws Exception {
        mvc.perform(get("/test/no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("资源不存在"));
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        mvc.perform(post("/test/api-exception"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMediaTypeReturns415() throws Exception {
        mvc.perform(post("/test/validate").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unexpectedExceptionReturns500WithoutInternals() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器开小差了，请稍后重试"))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(content().string(not(containsString("secret-db-password"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("at com.xiaoying"))));
    }

    record DemoRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{3,20}$") String username,
            @NotBlank @Size(min = 8, max = 64) String password) {
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/api-exception")
        void apiException() {
            throw new ApiException(ErrorCode.IMAGE_COUNT_MISMATCH, "需要 2 张图片", Map.of("expected", 2, "actual", 1));
        }

        @GetMapping("/test/not-found-business")
        void notFoundBusiness() {
            throw new ApiException(ErrorCode.TASK_NOT_FOUND);
        }

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody DemoRequest request) {
        }

        @GetMapping("/test/tasks/{taskId}")
        void task(@PathVariable long taskId) {
        }

        @GetMapping("/test/too-large")
        void tooLarge() {
            throw new MaxUploadSizeExceededException(5 * 1024 * 1024);
        }

        @GetMapping("/test/no-resource")
        void noResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/test/no-resource", "no-resource");
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("connect failed: password=secret-db-password");
        }
    }
}
