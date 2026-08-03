package com.hdu.apisensitivities.monitor;

import com.hdu.apisensitivities.service.monitor.MonitorService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调用监控统计服务集成测试（H2 内存库 + schema.sql 预置演示数据）。
 * 断言采用自洽性 + 增量方式，避免被其他测试类写入的审计数据干扰。
 */
@SpringBootTest
class MonitorServiceTest {

    @Autowired
    private MonitorService monitorService;

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

    private long sumLong(List<Map<String, Object>> list, String key) {
        return list.stream().mapToLong(m -> ((Number) m.get(key)).longValue()).sum();
    }

    @Test
    void overview_counts_selfConsistent() {
        Map<String, Object> ov = monitorService.getOverview(day);
        long todayTotal = ((Number) ov.get("todayTotal")).longValue();
        long pluginTotal = ((Number) ov.get("pluginTotal")).longValue();
        long apiTotal = ((Number) ov.get("apiTotal")).longValue();

        // 插件 + API 为总请求的子集（可能还有其它渠道）
        assertTrue(pluginTotal >= 0 && apiTotal >= 0);
        assertTrue(todayTotal >= pluginTotal + apiTotal,
                "总次数应不小于插件与 API 之和");
        // 渠道分组之和应等于总次数
        assertEquals(todayTotal, sumLong((List<Map<String, Object>>) ov.get("byChannel"), "cnt"),
                "渠道分组之和应等于今日总次数");
        // 分平台统计之和不应超过总次数（平台字段为空的记录不计入）
        long providerSum = sumLong((List<Map<String, Object>>) ov.get("byProvider"), "count");
        assertTrue(providerSum <= todayTotal, "分平台统计之和不应超过总次数");
        // 概览必须含异常告警计数
        assertNotNull(ov.get("anomalyCount"));
    }

    @Test
    void overview_standardizedProviders_includeDemoPlatforms() {
        List<Map<String, Object>> byProvider = monitorService.groupByStandardProvider(day);
        Map<String, Long> byCode = new java.util.HashMap<>();
        for (Map<String, Object> p : byProvider) {
            byCode.put(String.valueOf(p.get("code")), ((Number) p.get("count")).longValue());
        }
        // 演示数据包含 DeepSeek / ChatGPT / Kimi / 豆包 / 通义千问
        for (String code : new String[] { "DEEPSEEK", "OPENAI", "KIMI", "DOUBAO", "QWEN" }) {
            assertTrue(byCode.getOrDefault(code, 0L) >= 1, "标准化平台缺失: " + code);
        }
        // 每项结构：count == pluginCount + apiCount
        for (Map<String, Object> p : byProvider) {
            long count = ((Number) p.get("count")).longValue();
            long plugin = ((Number) p.get("pluginCount")).longValue();
            long api = ((Number) p.get("apiCount")).longValue();
            assertEquals(count, plugin + api,
                    "平台 " + p.get("code") + " 的 count 应等于 pluginCount + apiCount");
            assertNotNull(p.get("name"));
        }
    }

    @Test
    void overview_providerList_sortedDesc() {
        List<Map<String, Object>> byProvider = monitorService.groupByStandardProvider(day);
        for (int i = 1; i < byProvider.size(); i++) {
            long prev = ((Number) byProvider.get(i - 1).get("count")).longValue();
            long cur = ((Number) byProvider.get(i).get("count")).longValue();
            assertTrue(prev >= cur, "分平台统计应按次数降序排列");
        }
    }

    @Test
    void trend_defaultWindow_24points_totalMatches() {
        List<Map<String, Object>> points = monitorService.getTrend(day, 24);
        assertEquals(24, points.size(), "默认窗口应返回 24 个点");
        long sum = 0;
        for (Map<String, Object> p : points) {
            String hour = String.valueOf(p.get("hour"));
            assertTrue(hour.matches("\\d{2}:00"), "小时标签格式错误: " + hour);
            long plugin = ((Number) p.get("plugin")).longValue();
            long api = ((Number) p.get("api")).longValue();
            long total = ((Number) p.get("total")).longValue();
            assertEquals(plugin + api, total, "total 应等于 plugin + api");
            sum += total;
        }
        long todayTotal = ((Number) monitorService.getOverview(day).get("todayTotal")).longValue();
        assertEquals(todayTotal, sum, "趋势各小时之和应等于今日总次数");
    }

    @Test
    void trend_hoursParameter_respected() {
        List<Map<String, Object>> points = monitorService.getTrend(day, 6);
        assertEquals(6, points.size(), "hours=6 应返回 6 个点");
        List<Map<String, Object>> capped = monitorService.getTrend(day, 999);
        assertEquals(48, capped.size(), "hours 超上限应截断为 48");
    }

    @Test
    void controlledInsert_pluginAndApi_accuracy() {
        // 受控增量：插入 2 条 DeepSeek 插件 + 1 条 OpenAI API
        long deepseekTotalBefore = providerCount("DEEPSEEK");
        long deepseekPluginBefore = providerChannelCount("DEEPSEEK", "pluginCount");
        long openaiApiBefore = providerChannelCount("OPENAI", "apiCount");

        insertRow(PREFIX + "001", "u-acc-1", "BROWSER_PLUGIN", "DeepSeek", "ALLOW", "LOW");
        insertRow(PREFIX + "002", "u-acc-2", "BROWSER_PLUGIN", "deepseek-chat", "ALLOW", "LOW");
        insertRow(PREFIX + "003", "u-acc-3", "backend-api", "chatgpt", "ALLOW", "LOW");

        assertEquals(deepseekTotalBefore + 2, providerCount("DEEPSEEK"),
                "DEEPSEEK 总计数应 +2（含别名 deepseek-chat 归一）");
        assertEquals(deepseekPluginBefore + 2, providerChannelCount("DEEPSEEK", "pluginCount"),
                "DEEPSEEK 插件计数应 +2");
        assertEquals(openaiApiBefore + 1, providerChannelCount("OPENAI", "apiCount"),
                "OPENAI API 计数应 +1（chatgpt 别名归一）");
    }

    @Test
    void controlledInsert_chineseAlias_normalization() {
        long qwenBefore = providerCount("QWEN");
        insertRow(PREFIX + "011", "u-acc-4", "BROWSER_PLUGIN", "通义千问", "ALLOW", "LOW");
        insertRow(PREFIX + "012", "u-acc-5", "backend-api", "dashscope", "ALLOW", "LOW");
        assertEquals(qwenBefore + 2, providerCount("QWEN"), "中文别名与 dashscope 均应归一为 QWEN");
    }

    @Test
    void overview_afterInsert_totalIncrements() {
        long before = ((Number) monitorService.getOverview(day).get("todayTotal")).longValue();
        insertRow(PREFIX + "021", "u-acc-6", "BROWSER_PLUGIN", "Kimi", "ALLOW", "LOW");
        long after = ((Number) monitorService.getOverview(day).get("todayTotal")).longValue();
        assertEquals(before + 1, after, "插入一条调用后今日总次数应 +1");
    }

    private long providerCount(String code) {
        return monitorService.groupByStandardProvider(day).stream()
                .filter(m -> code.equals(String.valueOf(m.get("code"))))
                .mapToLong(m -> ((Number) m.get("count")).longValue())
                .sum();
    }

    private long providerChannelCount(String code, String field) {
        return monitorService.groupByStandardProvider(day).stream()
                .filter(m -> code.equals(String.valueOf(m.get("code"))))
                .mapToLong(m -> ((Number) m.get(field)).longValue())
                .sum();
    }
}
