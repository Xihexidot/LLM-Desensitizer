package com.hdu.apisensitivities.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 调用监控模块访问权限拦截器。
 * <p>
 * 监控页面仅允许安全审计（AUDITOR）、运维管理（OPERATOR）、系统管理员（ADMIN）角色访问。
 * 客户端通过请求头 {@code X-Monitor-Role} 声明角色（企业内部可替换为 SSO/JWT 解析）。
 * 无权限或角色非法时返回 403。
 * </p>
 */
@Component
public class MonitorAuthInterceptor implements HandlerInterceptor {

    public static final String ROLE_HEADER = "X-Monitor-Role";
    private static final Set<String> ALLOWED_ROLES = Set.of("AUDITOR", "ADMIN", "OPERATOR");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String role = request.getHeader(ROLE_HEADER);
        if (role != null && ALLOWED_ROLES.contains(role.trim().toUpperCase())) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"code\":403,\"message\":\"无监控访问权限：该模块仅限安全审计、运维管理相关角色访问\"}");
        return false;
    }
}
