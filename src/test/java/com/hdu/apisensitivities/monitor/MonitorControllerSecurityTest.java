package com.hdu.apisensitivities.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 调用监控接口权限校验测试。
 * 验证仅安全审计 / 运维管理相关角色（AUDITOR / ADMIN / OPERATOR）可访问，其余返回 403。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MonitorControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void noRoleHeader_forbidden() throws Exception {
        mockMvc.perform(get("/gateway/v1/monitor/overview"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownRole_forbidden() throws Exception {
        mockMvc.perform(get("/gateway/v1/monitor/overview").header("X-Monitor-Role", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditorRole_allowed() throws Exception {
        mockMvc.perform(get("/gateway/v1/monitor/overview").header("X-Monitor-Role", "AUDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayTotal").exists())
                .andExpect(jsonPath("$.pluginTotal").exists())
                .andExpect(jsonPath("$.apiTotal").exists())
                .andExpect(jsonPath("$.byProvider").isArray())
                .andExpect(jsonPath("$.byChannel").isArray())
                .andExpect(jsonPath("$.anomalyCount").exists());
    }

    @Test
    void adminRole_allowed() throws Exception {
        mockMvc.perform(get("/gateway/v1/monitor/overview").header("X-Monitor-Role", "ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void operatorRole_allowed() throws Exception {
        mockMvc.perform(get("/gateway/v1/monitor/overview").header("X-Monitor-Role", "OPERATOR"))
                .andExpect(status().isOk());
    }

    @Test
    void lowercaseRole_normalized_allowed() throws Exception {
        mockMvc.perform(get("/gateway/v1/monitor/overview").header("X-Monitor-Role", "auditor"))
                .andExpect(status().isOk());
    }

    @Test
    void trend_endpoint_respectsAuth() throws Exception {
        mockMvc.perform(get("/gateway/v1/monitor/trend").header("X-Monitor-Role", "AUDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours").value(24))
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points[0].hour").exists());
        mockMvc.perform(get("/gateway/v1/monitor/trend"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anomalies_endpoint_respectsAuth() throws Exception {
        mockMvc.perform(get("/gateway/v1/monitor/anomalies").header("X-Monitor-Role", "AUDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").exists())
                .andExpect(jsonPath("$.items").isArray());
        mockMvc.perform(get("/gateway/v1/monitor/anomalies").header("X-Monitor-Role", "GUEST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void preflightOptions_notForbidden() throws Exception {
        // CORS 预检请求应放行，不得返回 403
        mockMvc.perform(options("/gateway/v1/monitor/overview"))
                .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus(),
                        "OPTIONS 预检请求不应被权限拦截器拦截"));
    }
}
