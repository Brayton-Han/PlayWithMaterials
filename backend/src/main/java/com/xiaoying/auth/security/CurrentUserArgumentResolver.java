package com.xiaoying.auth.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;

/** 把拦截器解析出的 {@link CurrentUser} 注入 Controller 参数；缺失时按未登录处理（防御：接口误入匿名白名单）。 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object user = webRequest.getAttribute(CurrentUser.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (user instanceof CurrentUser currentUser) {
            return currentUser;
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }
}
