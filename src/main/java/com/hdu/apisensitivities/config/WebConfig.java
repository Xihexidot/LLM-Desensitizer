package com.hdu.apisensitivities.config;

import com.hdu.apisensitivities.interceptor.Interceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // 1. 统一使用 @Autowired 注入拦截器，删掉重复的字段和 final 声明
    @Autowired
    private Interceptor apiInterceptor;

    // 2. 合并为一个拦截器配置方法，整合了所有需要排除的路径
    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(apiInterceptor)
                .addPathPatterns("/api/**")       // 监控所有 API 路径
                .excludePathPatterns(             // 整合排除路径
                        "/api/health",
                        "/health",
                        "/swagger-ui/**",
                        "/v3/api-docs/**"
                );
    }

    // 3. 跨域配置保持不变
    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    // 4. RestTemplate 定义（确保项目中已经删除了 RestTemplateConfig.java 文件）
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
