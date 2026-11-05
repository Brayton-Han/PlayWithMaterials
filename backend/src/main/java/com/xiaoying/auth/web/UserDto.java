package com.xiaoying.auth.web;

import com.xiaoying.auth.domain.User;
import com.xiaoying.common.web.ApiTime;

/** api.yaml User。userId 以字符串输出；不含 passwordHash。 */
public record UserDto(String userId, String username, String email, String createdAt) {

    public static UserDto from(User user) {
        return new UserDto(String.valueOf(user.getId()), user.getUsername(), user.getEmail(),
                ApiTime.format(user.getCreatedAt()));
    }
}
