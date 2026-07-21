package com.hdu.apisensitivities.interceptor;

import com.hdu.apisensitivities.entity.ApiKey;
import com.hdu.apisensitivities.repository.ApiKeyRepository;
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

    private final ApiKeyRepository apiKeyRepository;

    public Interceptor(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        // 在请求处理之前进行调用
        String uri = request.getRequestURI();
        log.info("API访问拦截: {} {}", request.getMethod(), uri);

        // ========== API Key 认证逻辑 ==========
        // 浏览器插件 /plugin 端点暂不强制认证（保持兼容性）
        // 企业网关 /gateway/v1 端点必须认证
        if (uri.startsWith("/gateway/v1")) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("未提供有效的 Authorization header，拒绝访问: {}", uri);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"未提供有效的 Authorization header，请使用 Bearer <API Key>\"}");
                return false;
            }

            String apiKey = authHeader.substring(7); // 去掉 "Bearer "
            ApiKey authenticated = apiKeyRepository.findByPlainKey(apiKey).orElse(null);
            if (authenticated == null) {
                log.warn("无效的 API Key，拒绝访问: {}", uri);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"无效的 API Key，请检查或联系管理员\"}");
                return false;
            }

            // 认证通过，将身份信息写入请求属性
            request.setAttribute("authenticatedApiKey", authenticated);
            request.setAttribute("userId", authenticated.getUserId());
            request.setAttribute("department", authenticated.getDepartment());
            request.setAttribute("tenantId", authenticated.getTenantId());
            log.info("API Key 认证通过，应用: {}, 租户: {}, 用户: {}", authenticated.getName(), authenticated.getTenantId(), authenticated.getUserId());
        }

        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler, @Nullable Exception ex) throws Exception {
        if (ex != null) {
            log.error("API调用异常: {} {} - {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        }
        log.info("API调用完成: {} {} - 状态码: {}", request.getMethod(), request.getRequestURI(), response.getStatus());
    }
}
