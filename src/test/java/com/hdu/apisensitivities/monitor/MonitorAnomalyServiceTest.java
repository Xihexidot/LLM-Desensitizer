package com.hdu.apisensitivities.monitor;

import com.hdu.apisensitivities.service.monitor.MonitorAnomalyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常风险检测服务集成测试。
 * 通过受控插入构造触发阈值的数据，验证四类告警的识别有效性及员工标识脱敏合规。
 */
@SpringBootTest
class MonitorAnomalyServiceTest {

    @Autowired
    private MonitorAnomalyService anomalyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String day = LocalDate.now().toString();
    private static final String PREFIX = "tst-mon-";

    @BeforeEach
    @AfterEach
    void cleanupTestRows() {
        jdbcTemplate.update("DELETE FROM gateway_audit_event WHERE event_id LIKE ?", PREFIX + "%");
    }

    private void insertRow(String eventId, String userId, String channel, String provider,
            String decision, String risk) {
        jdbcTemplate.update(
                "INSERT INTO gateway_audit_event (event_id, timestamp, user_id, department, channel, "
                        + "target_provider, decision_action, input_risk_level) VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?)",
                eventId, userId, "监控测试部", channel, provider, decision, risk);
    }

    private Map<String, Object> find(List<Map<String, Object>> list, String type) {
        return list.stream().filter(a -> type.equals(String.valueOf(a.get("type")))).findFirst().orElse(null);
    }

    @Test
    void normalTraffic_noAlerts() {
        // 正常流量（低于全部阈值）不应触发告警
        insertRow(PREFIX + "001", "u-norm-1", "BROWSER_PLUGIN", "DeepSeek", "ALLOW", "LOW");
        insertRow(PREFIX + "002", "u-norm-2", "backend-api", "chatgpt", "ALLOW", "LOW");
        assertTrue(anomalyService.detect(day).isEmpty(), "正常流量不应产生告警");
    }

    @Test
    void highFrequency_20calls_sameUser_triggersHighAlert() {
        String userId = "u-hf-001";
        for (int i = 0; i < 20; i++) {
            insertRow(PREFIX + String.format("hf-%03d", i), userId, "BROWSER_PLUGIN", "DeepSeek", "ALLOW", "LOW");
        }
        List<Map<String, Object>> anomalies = anomalyService.detect(day);
        Map<String, Object> hit = find(anomalies, "HIGH_FREQUENCY");
        assertTrue(hit != null, "单用户高频调用应触发 HIGH_FREQUENCY 告警");
        assertEquals("HIGH", String.valueOf(hit.get("level")));
        assertEquals(20L, ((Number) hit.get("count")).longValue());
        String detail = String.valueOf(hit.get("detail"));
        // 员工标识脱敏合规：不得出现完整账号
        assertFalse(detail.contains(userId), "告警详情不得泄露完整员工账号");
        assertTrue(detail.contains("u-***"), "员工账号应以掩码形式展示");
    }

    @Test
    void riskSpike_10HighRiskEvents_triggersAlert() {
        for (int i = 0; i < 10; i++) {
            insertRow(PREFIX + String.format("rs-%03d", i), "u-rs-001", "backend-api", "DeepSeek", "BLOCK", "HIGH");
        }
        List<Map<String, Object>> anomalies = anomalyService.detect(day);
        Map<String, Object> hit = find(anomalies, "RISK_SPIKE");
        assertTrue(hit != null, "高风险事件达到阈值应触发 RISK_SPIKE 告警");
        assertEquals("HIGH", String.valueOf(hit.get("level")));
        assertTrue(((Number) hit.get("count")).longValue() >= 10);
    }

    @Test
    void blockSpike_10BlockDecisions_triggersAlert() {
        for (int i = 0; i < 10; i++) {
            insertRow(PREFIX + String.format("bs-%03d", i), "u-bs-001", "BROWSER_PLUGIN", "Kimi", "BLOCK", "LOW");
        }
        List<Map<String, Object>> anomalies = anomalyService.detect(day);
        Map<String, Object> hit = find(anomalies, "BLOCK_SPIKE");
        assertTrue(hit != null, "阻断事件达到阈值应触发 BLOCK_SPIKE 告警");
        assertEquals("MEDIUM", String.valueOf(hit.get("level")));
    }

    @Test
    void unknownProvider_5Calls_unregisteredPlatform_triggersAlert() {
        for (int i = 0; i < 5; i++) {
            insertRow(PREFIX + String.format("up-%03d", i), "u-up-001", "BROWSER_PLUGIN", "mystery-llm-xyz", "ALLOW", "LOW");
        }
        List<Map<String, Object>> anomalies = anomalyService.detect(day);
        Map<String, Object> hit = find(anomalies, "UNKNOWN_PROVIDER");
        assertTrue(hit != null, "未登记平台调用达到阈值应触发 UNKNOWN_PROVIDER 告警");
        assertEquals("MEDIUM", String.valueOf(hit.get("level")));
        String detail = String.valueOf(hit.get("detail"));
        assertTrue(detail.contains("mystery-llm-xyz"), "告警应携带原始平台标识供排查");
    }

    @Test
    void alerts_sortedByLevelHighFirst() {
        // 同时构造高频 + 未识别平台两类告警，验证按等级降序输出
        for (int i = 0; i < 20; i++) {
            insertRow(PREFIX + String.format("sort-hf-%03d", i), "u-sort-001", "BROWSER_PLUGIN", "DeepSeek", "ALLOW", "LOW");
        }
        for (int i = 0; i < 5; i++) {
            insertRow(PREFIX + String.format("sort-up-%03d", i), "u-sort-002", "backend-api", "shadow-ai-vendor", "ALLOW", "LOW");
        }
        List<Map<String, Object>> anomalies = anomalyService.detect(day);
        assertTrue(find(anomalies, "HIGH_FREQUENCY") != null);
        assertTrue(find(anomalies, "UNKNOWN_PROVIDER") != null);
        // 首位应为 HIGH 等级告警
        assertEquals("HIGH", String.valueOf(anomalies.get(0).get("level")), "告警应按等级降序排列");
    }
}
