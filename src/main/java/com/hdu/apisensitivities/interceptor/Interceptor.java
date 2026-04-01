package com.hdu.apisensitivities.interceptor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Slf4j
@Component
public class Interceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        // 在请求处理之前进行调用
        log.info("API访问拦截: {} {}", request.getMethod(), request.getRequestURI());

        // 可以在这里添加具体的拦截逻辑
        // 例如：权限检查、日志记录、请求参数校验等

        // 返回true表示继续执行后续操作，返回false表示中断请求
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler, @Nullable Exception ex) throws Exception {
        // 在整个请求结束之后被调用，用于资源清理
        if (ex != null) {
            log.error("API调用异常: {} {} - {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        }
        log.info("API调用完成: {} {} - 状态码: {}", request.getMethod(), request.getRequestURI(), response.getStatus());
    }
}
