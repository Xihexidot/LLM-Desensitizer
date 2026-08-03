package com.hdu.apisensitivities.service.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业员工外部 LLM 调用监控统计服务。
 * <p>
 * 仅统计企业员工通过内部插件（BROWSER_PLUGIN）或统一网关 API（backend-api）发往
 * 外部大模型平台的请求次数，按平台类型维度拆分；数据仅用于内部安全审计与异常风险检测，
 * 不涉及员工敏感内容（审计原文已脱敏/加密存储）。
 * </p>
 */
@Slf4j
@Service
public class MonitorService {

    private final JdbcTemplate jdbcTemplate;
    private final MonitorAnomalyService anomalyService;

    public MonitorService(JdbcTemplate jdbcTemplate, MonitorAnomalyService anomalyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.anomalyService = anomalyService;
    }

    /**
     * 当日监控概览：总次数、渠道拆分、分平台统计、风险/决策分布、异常告警数。
     */
    public Map<String, Object> getOverview(String day) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", day);
        result.put("todayTotal", count("SELECT COUNT(*) FROM gateway_audit_event WHERE timestamp >= ?", day));
        result.put("pluginTotal", count(
                "SELECT COUNT(*) FROM gateway_audit_event WHERE timestamp >= ? AND channel = 'BROWSER_PLUGIN'", day));
        result.put("apiTotal", count(
                "SELECT COUNT(*) FROM gateway_audit_event WHERE timestamp >= ? AND channel = 'backend-api'", day));

        result.put("byChannel", jdbcTemplate.queryForList(
                "SELECT channel, COUNT(*) AS cnt FROM gateway_audit_event "
                        + "WHERE timestamp >= ? GROUP BY channel",
                day));

        result.put("byProvider", groupByStandardProvider(day));

        result.put("byRiskLevel", jdbcTemplate.queryForList(
                "SELECT input_risk_level, COUNT(*) AS cnt FROM gateway_audit_event "
                        + "WHERE timestamp >= ? GROUP BY input_risk_level",
                day));

        result.put("byDecision", jdbcTemplate.queryForList(
                "SELECT decision_action, COUNT(*) AS cnt FROM gateway_audit_event "
                        + "WHERE timestamp >= ? AND decision_action IS NOT NULL GROUP BY decision_action",
                day));

        List<Map<String, Object>> anomalies = anomalyService.detect(day);
        result.put("anomalyCount", anomalies.size());
        return result;
    }

    /**
     * 分平台统计（平台标准化 + 插件/API 渠道拆分），按总量降序。
     */
    public List<Map<String, Object>> groupByStandardProvider(String day) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT target_provider, channel, COUNT(*) AS cnt FROM gateway_audit_event "
                        + "WHERE timestamp >= ? AND target_provider IS NOT NULL "
                        + "GROUP BY target_provider, channel",
                day);

        // code -> {provider,name,count,pluginCount,apiCount}
        Map<String, Map<String, Object>> acc = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String raw = String.valueOf(row.get("target_provider"));
            String channel = row.get("channel") == null ? "" : String.valueOf(row.get("channel"));
            long cnt = ((Number) row.get("cnt")).longValue();

            var info = com.hdu.apisensitivities.utils.ProviderNormalizer.normalize(raw);
            Map<String, Object> item = acc.computeIfAbsent(info.code(),
                    k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", info.code());
                        m.put("name", info.name());
                        m.put("count", 0L);
                        m.put("pluginCount", 0L);
                        m.put("apiCount", 0L);
                        return m;
                    });
            item.put("count", ((Number) item.get("count")).longValue() + cnt);
            if ("BROWSER_PLUGIN".equalsIgnoreCase(channel)) {
                item.put("pluginCount", ((Number) item.get("pluginCount")).longValue() + cnt);
            } else if ("backend-api".equalsIgnoreCase(channel)) {
                item.put("apiCount", ((Number) item.get("apiCount")).longValue() + cnt);
            }
        }

        return acc.values().stream()
                .sorted((a, b) -> Long.compare(
                        ((Number) b.get("count")).longValue(), ((Number) a.get("count")).longValue()))
                .toList();
    }

    /**
     * 按小时调用趋势（近 hours 小时，缺省的窗口补 0），拆分插件/API 两条曲线。
     */
    public List<Map<String, Object>> getTrend(String day, int hours) {
        int h = Math.max(1, Math.min(hours, 48));
        // 取当日最近 h 小时（含跨日场景简化为当日 0 点起）
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT HOUR(timestamp) AS hr, channel, COUNT(*) AS cnt FROM gateway_audit_event "
                        + "WHERE timestamp >= ? GROUP BY HOUR(timestamp), channel",
                day);

        Map<Integer, Map<String, Object>> byHour = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            int hr = ((Number) row.get("hr")).intValue();
            String channel = row.get("channel") == null ? "" : String.valueOf(row.get("channel"));
            long cnt = ((Number) row.get("cnt")).longValue();
            Map<String, Object> item = byHour.computeIfAbsent(hr, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("hour", String.format("%02d:00", k));
                m.put("plugin", 0L);
                m.put("api", 0L);
                m.put("total", 0L);
                return m;
            });
            if ("BROWSER_PLUGIN".equalsIgnoreCase(channel)) {
                item.put("plugin", cnt);
            } else if ("backend-api".equalsIgnoreCase(channel)) {
                item.put("api", cnt);
            }
            item.put("total", ((Number) item.get("total")).longValue() + cnt);
        }

        List<Map<String, Object>> trend = new java.util.ArrayList<>();
        int endHour = java.time.LocalTime.now().getHour();
        int startHour = endHour - h + 1;
        for (int i = startHour; i <= endHour; i++) {
            int hr = ((i % 24) + 24) % 24;
            trend.add(byHour.getOrDefault(hr, zeroHour(hr)));
        }
        return trend;
    }

    private Map<String, Object> zeroHour(int hr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hour", String.format("%02d:00", hr));
        m.put("plugin", 0L);
        m.put("api", 0L);
        m.put("total", 0L);
        return m;
    }

    private long count(String sql, String day) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class, day);
        return v != null ? v : 0L;
    }
}
