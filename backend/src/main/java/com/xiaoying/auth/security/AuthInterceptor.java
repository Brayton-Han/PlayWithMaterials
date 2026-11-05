package com.xiaoying.auth.security;

import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.xiaoying.auth.service.SessionService;
import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;

/**
 * 默认拒绝（architecture §0、§4.3）：对 {@link #PROTECTED_PATH_PATTERNS} 生效，
 * 除匿名白名单外，没有有效会话（缺失 / 过期 / 用户已删除）一律 401 {@code UNAUTHENTICATED}。
 * 有效时把 {@link CurrentUser} 放入请求属性。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 拦截器生效范围。 */
    public static final String[] PROTECTED_PATH_PATTERNS = {"/api/**", "/files/uploads/**", "/files/results/**"};

    /** 匿名可访问的"方法 + 路径"（api.yaml 中没有 security 的接口）。method 为 null 表示任意方法。 */
    private static final List<Rule> ANONYMOUS = List.of(
            new Rule(Set.of(HttpMethod.GET, HttpMethod.HEAD), "/api/categories"),
            new Rule(Set.of(HttpMethod.GET, HttpMethod.HEAD), "/api/templates"),
            new Rule(Set.of(HttpMethod.GET, HttpMethod.HEAD), "/api/templates/**"),
            new Rule(Set.of(HttpMethod.POST), "/api/auth/register"),
            new Rule(Set.of(HttpMethod.POST), "/api/auth/login"),
            new Rule(Set.of(HttpMethod.POST), "/api/auth/logout"),
            // 模拟控制接口不需要登录（DQ-01）；只在 dev / demo profile 注册（app.simulation.dev-api-enabled），否则 404
            new Rule(null, "/api/dev/**"));

    private final SessionService sessionService;

    public AuthInterceptor(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isAnonymousAllowed(request)) {
            return true;
        }
        CurrentUser user = sessionService.resolve(SessionCookies.read(request))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE, user);
        return true;
    }

    static boolean isAnonymousAllowed(HttpServletRequest request) {
        // 与 Spring MVC 路由匹配使用同一份解析结果，避免鉴权与路由对路径理解不一致
        PathContainer path = (ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request)
                : ServletRequestPathUtils.parseAndCache(request)).pathWithinApplication();
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        return ANONYMOUS.stream().anyMatch(rule -> rule.matches(method, path));
    }

    private record Rule(Set<HttpMethod> methods, PathPattern pattern) {

        Rule(Set<HttpMethod> methods, String pattern) {
            this(methods, PathPatternParser.defaultInstance.parse(pattern));
        }

        boolean matches(HttpMethod method, PathContainer path) {
            return (methods == null || methods.contains(method)) && pattern.matches(path);
        }
    }
}
