package com.xiaoying.task.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;
import com.xiaoying.task.domain.Task;
import com.xiaoying.task.domain.TaskAsset;
import com.xiaoying.task.domain.TaskView;
import com.xiaoying.task.mapper.TaskAssetMapper;
import com.xiaoying.task.mapper.TaskMapper;

/**
 * 任务查询（F-07、F-11）。纯只读：只调用 Mapper 的读方法，不推进、不修正任何任务状态（R-05.2）。
 * 所有查询都带当前用户 ID；他人或不存在的任务一律 404 {@code TASK_NOT_FOUND}，不暴露存在性。
 */
@Service
@Transactional(readOnly = true)
public class TaskQueryService {

    private final TaskMapper taskMapper;
    private final TaskAssetMapper assetMapper;

    public TaskQueryService(TaskMapper taskMapper, TaskAssetMapper assetMapper) {
        this.taskMapper = taskMapper;
        this.assetMapper = assetMapper;
    }

    /** 单任务（Q6 + Q10）。 */
    public TaskView get(long userId, long taskId) {
        Task task = taskMapper.findByIdAndUser(taskId, userId);
        if (task == null) {
            throw new ApiException(ErrorCode.TASK_NOT_FOUND);
        }
        return new TaskView(task, assetMapper.listByTaskIdsAndUser(userId, List.of(task.getId())));
    }

    /**
     * 当前用户的任务，{@code created_at DESC, id DESC}，不分页 [OQ-09 余项临时默认]。
     * {@code templateId} 为 null 或空白时返回全部模板的任务（我的记录，Q8），否则只返回该模板的（详情页 History，Q7）；
     * 不存在的模板返回空列表。素材一次批量加载（Q10），避免 N+1。
     */
    public List<TaskView> list(long userId, String templateId) {
        String filter = (templateId == null || templateId.isBlank()) ? null : templateId;
        List<Task> tasks = taskMapper.listByUser(userId, filter);
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, List<TaskAsset>> assetsByTask = assetMapper
                .listByTaskIdsAndUser(userId, tasks.stream().map(Task::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(TaskAsset::getTaskId));
        return tasks.stream()
                .map(t -> new TaskView(t, assetsByTask.getOrDefault(t.getId(), List.of())))
                .toList();
    }
}
