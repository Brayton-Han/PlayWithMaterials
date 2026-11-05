package com.xiaoying.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.xiaoying.auth.domain.User;

@Mapper
public interface UserMapper {

    /** 用户名是否已被占用：不区分大小写（_ci 排序规则），包含已删除账户（OQ-31 临时默认）。 */
    boolean existsByUsername(@Param("username") String username);

    /** 邮箱是否被未删除账户使用：不区分大小写（OQ-30 临时默认）。 */
    boolean existsActiveEmail(@Param("email") String email);

    /** 插入后回填 {@code id}。唯一键冲突抛 {@code DuplicateKeyException}。 */
    void insert(User user);

    /** 登录用：只查未删除账户，用户名不区分大小写。 */
    User findActiveByUsername(@Param("username") String username);

    User findActiveById(@Param("id") long id);
}
