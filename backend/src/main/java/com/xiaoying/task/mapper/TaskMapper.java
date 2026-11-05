package com.xiaoying.task.mapper;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.xiaoying.task.domain.FailureCode;
import com.xiaoying.task.domain.Task;
import com.xiaoying.task.domain.TaskStage;

/**
 * {@code tasks} 表（database.md §2.5）。
 * <p>
 * 读方法都带 {@code userId}（architecture §3.1 规则 3），结果关联模板标题和封面。
 * 状态只能通过下面的条件更新修改：返回影响行数，0 表示任务已是终态（或不满足前置状态），
 * 调用方据此停止执行，终态永远不会被覆盖（R-05.3）。
 */
@Mapper
public interface TaskMapper {

    /** 插入 {@code status='PENDING', stage=NULL}；回填 {@code id}。{@code createdAt} 等由数据库默认值生成。 */
    void insert(Task task);

    /** 单任务（Q6）；不存在或不属于该用户时返回 null。 */
    Task findByIdAndUser(@Param("id") long id, @Param("userId") long userId);

    /**
     * 当前用户的任务，{@code created_at DESC, id DESC}。
     * {@code templateId} 为 null 时返回全部（我的记录，Q8），否则只返回该模板的（详情页 History，Q7）。
     */
    List<Task> listByUser(@Param("userId") long userId, @Param("templateId") String templateId);

    /** 批量查询（Q9）：只返回属于该用户的；{@code ids} 为空时返回空列表。排序同 {@link #listByUser}。 */
    List<Task> listByIdsAndUser(@Param("userId") long userId, @Param("ids") Collection<Long> ids);

    /** 每步开始：{@code WHERE status IN ('PENDING','PROCESSING')}；首次调用写入 {@code started_at}。 */
    int markProcessing(@Param("id") long id, @Param("stage") TaskStage stage);

    /** 全部成功：{@code WHERE status='PROCESSING' AND stage='RESULT_ORGANIZING'}。 */
    int markSucceeded(@Param("id") long id, @Param("resultUrl") String resultUrl);

    /** 步骤失败 / 兜底：{@code WHERE status IN ('PENDING','PROCESSING')}。{@code stage} 可为 null（尚未开始）。 */
    int markFailed(@Param("id") long id, @Param("stage") TaskStage stage,
                   @Param("code") FailureCode code, @Param("reason") String reason);

    /**
     * 删除账户时废弃该用户进行中的任务 [OQ-26 余项临时默认]：
     * FAILED + {@code ACCOUNT_DELETED}，{@code failed_stage} = 当时所处阶段（PENDING 时为 NULL）。返回废弃的任务数。
     */
    int abandonActiveByUser(@Param("userId") long userId);

    /** 派发被拒绝时的补偿删除 [OQ-12]：只删 PENDING 任务，{@code task_assets} 级联删除。 */
    int deleteById(@Param("id") long id);
}
