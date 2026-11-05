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
}
