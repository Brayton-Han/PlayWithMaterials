package com.xiaoying.task.web;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.xiaoying.auth.security.CurrentUser;
import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;
import com.xiaoying.common.web.ItemsDto;
import com.xiaoying.task.service.TaskQueryService;

/**
 * {@code GET /api/tasks}、{@code GET /api/tasks/{taskId}}（api.yaml tag tasks，需要登录）。
 * 两个接口都是纯只读的（R-05.2）。{@code ids} 批量查询为 P1（T-073），此处尚未实现。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskQueryService queryService;

    public TaskController(TaskQueryService queryService) {
        this.queryService = queryService;
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
}
