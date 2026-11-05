package com.xiaoying.task.mapper;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.xiaoying.task.domain.TaskAsset;

/** {@code task_assets} 表（database.md §2.6）。 */
@Mapper
public interface TaskAssetMapper {

    /** 插入后回填 {@code id}。同一任务重复的 {@code slot_index} 或重复的 {@code storage_key} 抛 {@code DuplicateKeyException}。 */
    void insert(TaskAsset asset);

    /**
     * 批量加载素材（Q10），按 {@code task_id, slot_index} 升序。
     * 额外 JOIN tasks 校验 {@code user_id}，不属于该用户的任务的素材不会返回；{@code taskIds} 为空时返回空列表。
     */
    List<TaskAsset> listByTaskIdsAndUser(@Param("userId") long userId, @Param("taskIds") Collection<Long> taskIds);
}
