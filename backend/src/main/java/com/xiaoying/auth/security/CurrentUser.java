package com.xiaoying.auth.security;

/**
 * 当前登录用户，由 {@link AuthInterceptor} 解析会话后放入请求属性，
 * Controller 方法声明该类型的参数即可拿到（{@link CurrentUserArgumentResolver}）。
 */
public record CurrentUser(long id, String username) {

    static final String REQUEST_ATTRIBUTE = CurrentUser.class.getName();
}
