package com.xiaoying.task.web;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

import com.xiaoying.auth.security.CurrentUser;
import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;
import com.xiaoying.common.error.FieldErrorDto;
import com.xiaoying.common.web.ItemsDto;
import com.xiaoying.task.domain.TaskView;
import com.xiaoying.task.service.TaskQueryService;
import com.xiaoying.task.service.TaskSubmissionService;

/**
 * {@code POST /api/tasks}（提交，T-041）、{@code GET /api/tasks}、{@code GET /api/tasks/{taskId}}
 * （api.yaml tag tasks，全部需要登录）。GET 接口是纯只读的（R-05.2）。{@code ids} 批量查询为 P1（T-073），此处尚未实现。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    static final int TEMPLATE_ID_MAX = 64;

    private final TaskQueryService queryService;
    private final TaskSubmissionService submissionService;

    public TaskController(TaskQueryService queryService, TaskSubmissionService submissionService) {
        this.queryService = queryService;
        this.submissionService = submissionService;
    }

    /**
     * 提交制作任务（api.yaml createTask）：{@code multipart/form-data}，{@code templateId} + 按上传位顺序的 {@code files}。
     * 成功 202 + {@code Location}，不等待制作完成（R-04）。
     * <p>
     * 不使用 {@code consumes} 限定：那样非 multipart 的请求会在鉴权拦截器之前被路由判为 415，
     * 匿名请求拿不到 401。这里先过鉴权（拦截器），再由方法体判断请求格式。
     */
    @PostMapping
    public ResponseEntity<TaskDto> create(
            CurrentUser user,
            HttpServletRequest request,
            @RequestParam(required = false) String templateId,
            @RequestParam(name = "files", required = false) List<MultipartFile> files) {
        if (WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class) == null) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        checkTemplateId(templateId);
        TaskView view = submissionService.submit(user.id(), templateId, files);
        TaskDto dto = TaskDtoAssembler.toDto(view);
        return ResponseEntity.accepted().location(URI.create("/api/tasks/" + dto.taskId())).body(dto);
    }

    @GetMapping
    public ItemsDto<TaskDto> list(
            CurrentUser user,
            @RequestParam(required = false) @Size(max = 64, message = "模板 ID 不能超过 64 个字符") String templateId) {
        return ItemsDto.of(queryService.list(user.id(), templateId).stream().map(TaskDtoAssembler::toDto).toList());
    }

    /** 非数字 → 400；符合格式但超出 long 范围的 ID 不可能存在 → 404。 */
    @GetMapping("/{taskId}")
    public TaskDto get(
            CurrentUser user,
            @PathVariable @Pattern(regexp = "^[0-9]{1,19}$", message = "任务 ID 格式不正确") String taskId) {
        long id;
        try {
            id = Long.parseLong(taskId);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.TASK_NOT_FOUND);
        }
        return TaskDtoAssembler.toDto(queryService.get(user.id(), id));
    }

    private static void checkTemplateId(String templateId) {
        FieldErrorDto error = null;
        if (templateId == null || templateId.isBlank()) {
            error = FieldErrorDto.of("templateId", "REQUIRED", "缺少模板 ID");
        } else if (templateId.length() > TEMPLATE_ID_MAX) {
            error = FieldErrorDto.of("templateId", "LENGTH", "模板 ID 不能超过 64 个字符");
        }
        if (error != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    Map.of("fieldErrors", List.of(error)));
        }
    }
}
