package com.xiaoying.auth.mapper;

import java.time.Instant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.xiaoying.auth.domain.User;

@Mapper
public interface SessionMapper {

    void insert(@Param("tokenHash") String tokenHash, @Param("userId") long userId,
                @Param("createdAt") Instant createdAt, @Param("expiresAt") Instant expiresAt);

    /** 会话有效（未过期）且用户未删除时返回该用户，否则 null。 */
    User findActiveUserByTokenHash(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    int deleteByTokenHash(@Param("tokenHash") String tokenHash);

    /** 修改密码后作废其他会话（OQ-25 余项临时默认）：删除该用户除 {@code keepTokenHash} 外的全部会话。 */
    int deleteByUserIdExcept(@Param("userId") long userId, @Param("keepTokenHash") String keepTokenHash);

    /** 删除账户时作废全部会话。 */
    int deleteByUserId(@Param("userId") long userId);
}
