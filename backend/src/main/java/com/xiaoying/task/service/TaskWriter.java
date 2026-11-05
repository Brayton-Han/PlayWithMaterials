package com.xiaoying.task.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.xiaoying.task.domain.Task;
import com.xiaoying.task.domain.TaskAsset;
import com.xiaoying.task.domain.TaskView;
import com.xiaoying.task.mapper.TaskAssetMapper;
import com.xiaoying.task.mapper.TaskMapper;

/**
 * 任务受理的数据库部分（architecture §5.1）：单个事务内写入 {@code tasks(PENDING)} 与全部 {@code task_assets}。
 * 独立成 bean，使 {@link TaskSubmissionService} 能在事务<b>提交之后</b>再 commit 文件、派发执行器。
 */
@Service
public class TaskWriter {

    private final TaskMapper taskMapper;
    private final TaskAssetMapper assetMapper;

    public TaskWriter(TaskMapper taskMapper, TaskAssetMapper assetMapper) {
        this.taskMapper = taskMapper;
        this.assetMapper = assetMapper;
    }

    /**
     * 插入任务与素材，并在同一事务内回读完整视图（{@code createdAt} 等由数据库生成；
     * 回读发生在派发之前，所以返回的状态一定是 PENDING）。任一写入失败整体回滚。
     */
    @Transactional
    public TaskView create(long userId, String templateId, String simConfig, List<NewAsset> assets) {
        Task task = new Task(userId, templateId, simConfig);
        taskMapper.insert(task);
        for (NewAsset a : assets) {
            assetMapper.insert(new TaskAsset(task.getId(), a.slotIndex(), a.file().storageKey(), a.originalName(),
                    a.file().mimeType(), a.file().sizeBytes()));
        }
        Task saved = taskMapper.findByIdAndUser(task.getId(), userId);
        return new TaskView(saved, assetMapper.listByTaskIdsAndUser(userId, List.of(task.getId())));
    }
}
