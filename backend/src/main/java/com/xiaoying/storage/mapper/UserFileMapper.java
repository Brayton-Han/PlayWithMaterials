package com.xiaoying.storage.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户文件归属查询（DQ-02，database.md Q13 / Q14）。只读 {@code task_assets}、{@code tasks}，
 * 两个方法都带 {@code userId}：不属于该用户（或不存在）时返回 null。
 */
@Mapper
public interface UserFileMapper {

    /** Q13：{@code storageKey} 对应的素材属于该用户的任务时返回其记录。 */
    UserFileRow findUpload(@Param("userId") long userId, @Param("storageKey") String storageKey);

    /** Q14：任务属于该用户且 {@code result_url} 与请求路径一致时返回 {@code result_url}。 */
    String findResultUrl(@Param("userId") long userId, @Param("taskId") long taskId,
                         @Param("resultUrl") String resultUrl);
}
