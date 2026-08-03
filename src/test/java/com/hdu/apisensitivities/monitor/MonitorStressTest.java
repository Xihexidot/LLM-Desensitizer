package com.hdu.apisensitivities.monitor;

import com.hdu.apisensitivities.service.monitor.MonitorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调用监控统计压力测试。
 * 插入 1200 条千级请求数据，验证统计聚合接口在千级数据量下响应耗时与结果准确性。
 * 事务回滚，避免污染共享 H2 库。
 */
@SpringBootTest
@Transactional
class MonitorStressTest {

    @Autowired
    private MonitorService monitorService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String day = LocalDate.now().toString();
    private static final String[] PROVIDERS = {
            "OpenAI", "Claude", "DeepSeek", "通义千问", "文心一言", "豆包",
            "Kimi", "混元", "Gemini", "Perplexity", "Ollama", "unknown-vendor"
    };

    private static final int ROWS = 1200;

    @Test
    void thousandLevelData_overviewAggregation_withinBudget() {
        seedRows();
        long t0 = System.nanoTime();
        Map<String, Object> ov = monitorService.getOverview(day);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        long todayTotal = ((Number) ov.get("todayTotal")).longValue();
        assertTrue(todayTotal >= ROWS, "千级数据应被完整统计, 实际: " + todayTotal);
        assertEquals(todayTotal,
                ((Number) ov.get("pluginTotal")).longValue() + ((Number) ov.get("apiTotal")).longValue(),
                "插件与 API 之和应等于总次数");
        assertTrue(elapsedMs < 3000, "千级数据概览聚合耗时超预算: " + elapsedMs + "ms");
    }

    @Test
    void thousandLevelData_groupByProvider_completeAndFast() {
        seedRows();
        long t0 = System.nanoTime();
        List<Map<String, Object>> byProvider = monitorService.groupByStandardProvider(day);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(12, byProvider.size(), "12 个标准平台应全部出现");
        long sum = byProvider.stream().mapToLong(m -> ((Number) m.get("count")).longValue()).sum();
        assertTrue(sum >= ROWS, "分平台统计之和应覆盖千级数据, 实际: " + sum);
        assertTrue(elapsedMs < 3000, "千级数据分平台聚合耗时超预算: " + elapsedMs + "ms");
    }

    @Test
    void thousandLevelData_trend_fastAndSelfConsistent() {
        seedRows();
        long t0 = System.nanoTime();
        List<Map<String, Object>> points = monitorService.getTrend(day, 24);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(24, points.size(), "趋势窗口应完整");
        long sum = points.stream().mapToLong(m -> ((Number) m.get("total")).longValue()).sum();
        assertTrue(sum >= ROWS, "趋势总和应覆盖千级数据, 实际: " + sum);
        for (Map<String, Object> p : points) {
            long plugin = ((Number) p.get("plugin")).longValue();
            long api = ((Number) p.get("api")).longValue();
            assertEquals(plugin + api, ((Number) p.get("total")).longValue(), "total 应等于 plugin+api");
        }
        assertTrue(elapsedMs < 3000, "千级数据趋势聚合耗时超预算: " + elapsedMs + "ms");
    }

    private void seedRows() {
        String sql = "INSERT INTO gateway_audit_event (event_id, timestamp, user_id, department, channel, "
                + "target_provider, decision_action, input_risk_level) VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?)";
        for (int i = 0; i < ROWS; i++) {
            String provider = PROVIDERS[i % PROVIDERS.length];
            String channel = (i % 2 == 0) ? "BROWSER_PLUGIN" : "backend-api";
            String risk = (i % 5 == 0) ? "HIGH" : ((i % 3 == 0) ? "MEDIUM" : "LOW");
            String decision = (i % 7 == 0) ? "BLOCK" : "ALLOW";
            jdbcTemplate.update(sql, "tst-stress-" + i, "u-stress-" + (i % 20), "压测部",
                    channel, provider, decision, risk);
        }
    }
}
