package com.xiaoying.storage.web;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.xiaoying.auth.security.CurrentUser;
import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;
import com.xiaoying.storage.UserFile;
import com.xiaoying.storage.UserFileService;

/**
 * 用户素材与结果图（api.yaml getUploadedFile / getResultFile，DQ-02，T-047）。
 * <p>
 * 鉴权由 {@code AuthInterceptor} 负责（覆盖 {@code /files/uploads/**}、{@code /files/results/**}）：没有有效会话 → 401。
 * 之后按数据库确认归属：他人的、不存在的、路径参数不符合 api.yaml pattern 的一律 404 {@code NOT_FOUND}
 * （契约只列出 200 / 401 / 404，格式错误也不暴露存在性）。响应头 {@code Cache-Control: private, no-store}。
 */
@RestController
public class UserFileController {

    private static final Pattern YEAR = Pattern.compile("^[0-9]{4}$");
    private static final Pattern MONTH = Pattern.compile("^[0-9]{2}$");
    private static final Pattern TASK_ID = Pattern.compile("^[0-9]{1,19}$");
    private static final Pattern FILE_NAME = Pattern.compile("^[0-9a-f-]{36}\\.(jpg|png|webp)$");

    /** api.yaml components.responses.UserFile：浏览器不缓存，删除账户后本机也不会再显示。 */
    static final String CACHE_CONTROL = "private, no-store";

    private final UserFileService service;

    public UserFileController(UserFileService service) {
        this.service = service;
    }

    @GetMapping("/files/uploads/{yyyy}/{mm}/{fileName}")
    public ResponseEntity<Resource> upload(CurrentUser user, @PathVariable String yyyy, @PathVariable String mm,
                                           @PathVariable String fileName) {
        if (!matches(YEAR, yyyy) || !matches(MONTH, mm) || !matches(FILE_NAME, fileName)) {
            throw notFound();
        }
        return respond(service.findUpload(user.id(), yyyy, mm, fileName));
    }

    @GetMapping("/files/results/{taskId}/{fileName}")
    public ResponseEntity<Resource> result(CurrentUser user, @PathVariable String taskId,
                                           @PathVariable String fileName) {
        if (!matches(TASK_ID, taskId) || !matches(FILE_NAME, fileName)) {
            throw notFound();
        }
        long id;
        try {
            id = Long.parseLong(taskId);
        } catch (NumberFormatException e) {
            throw notFound();   // 19 位但超出 long 范围：不可能存在
        }
        return respond(service.findResult(user.id(), id, fileName));
    }

    private static ResponseEntity<Resource> respond(Optional<UserFile> file) {
        UserFile f = file.orElseThrow(UserFileController::notFound);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(f.type().mimeType()))
                .body(new FileSystemResource(f.path()));
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND);
    }
}
