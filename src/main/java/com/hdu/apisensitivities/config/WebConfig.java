package com.hdu.apisensitivities.config;

import com.hdu.apisensitivities.interceptor.Interceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    //跨域配置类
    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/**")  // 允许所有路径
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*") // 允许本地所有端口（开发环境）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 允许的 HTTP 方法
                .allowedHeaders("*") // 允许所有请求头
                .allowCredentials(true) // 允许携带Cookie，便于开发环境调试
                .maxAge(3600); // 预检请求缓存时间（秒）
    }

    //拦截器设置类
    private final Interceptor apiInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiInterceptor)
                .addPathPatterns("/api/**")       // 监控所有API路径
                .excludePathPatterns(             // 排除路径
                        "/health",
                        "/swagger-ui/**",
                        "/v3/api-docs/**"
                );
    }
}
