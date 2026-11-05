package com.xiaoying.common.web;

import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.xiaoying.auth.security.AuthInterceptor;
import com.xiaoying.auth.security.CurrentUserArgumentResolver;

/** 注册鉴权拦截器、{@code CurrentUser} 参数解析器（architecture §4.3）与种子静态文件映射（T-020）。 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(AuthInterceptor.PROTECTED_PATH_PATTERNS);
    }

    /**
     * 只把种子封面 / 示例图 {@code /files/seed/**} 映射到 {@code classpath:/seed/}（公开，api.yaml getSeedFile）。
     * 用户素材和结果图（/files/uploads、/files/results）不做静态映射，由 T-047 的 Controller 校验归属后返回。
     * 资源处理器本身拒绝路径穿越，且不提供目录列举。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/files/seed/**")
                .addResourceLocations("classpath:/seed/")
                .setCacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic());
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
