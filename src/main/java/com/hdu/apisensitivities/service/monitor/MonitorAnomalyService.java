package com.hdu.apisensitivities.service.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常风险检测与告警服务。
 * <p>
 * 基于当日审计聚合数据识别异常调用行为，输出可供监控页告警面板展示的风险事件，
 * 支撑"异常风险检测"这一企业安全审计核心场景。检测项均为统计口径，不触碰员工敏感内容。
 * </p>
 */
@Slf4j
@Service
public class MonitorAnomalyService {

    private final JdbcTemplate jdbcTemplate;

    public MonitorAnomalyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 执行异常检测，返回告警列表（按等级降序）。
     */
    public List<Map<String, Object>> detect(String day) {
        List<Map<String, Object>> anomalies = new ArrayList<>();

        detectHighFrequency(day, anomalies);
        detectRiskSpike(day, anomalies);
        detectBlockSpike(day, anomalies);
        detectUnknownProvider(day, anomalies);

        // 按等级降序：高风险告警优先展示
        anomalies.sort((a, b) -> levelWeight(String.valueOf(b.get("level")))
                - levelWeight(String.valueOf(a.get("level"))));
        return anomalies;
    }

    /** 1. 高频调用：当日内单用户调用次数超过阈值（疑似脚本化滥用/数据爬取） */
    private void detectHighFrequency(String day, List<Map<String, Object>> anomalies) {
        final long threshold = 20L;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, COUNT(*) AS cnt FROM gateway_audit_event "
                        + "WHERE timestamp >= ? AND user_id IS NOT NULL AND user_id <> '' "
                        + "GROUP BY user_id HAVING COUNT(*) >= ? ORDER BY cnt DESC LIMIT 5",
                day, threshold);
        for (Map<String, Object> row : rows) {
            String userId = String.valueOf(row.get("user_id"));
            long cnt = ((Number) row.get("cnt")).longValue();
            anomalies.add(build("HIGH", "HIGH_FREQUENCY", "单用户高频调用",
                    "员工账号 " + maskUserId(userId) + " 当日调用外部 LLM 平台 " + cnt + " 次，超过阈值 "
                            + threshold + "，疑似脚本化滥用或批量数据外发。",
                    cnt, "当日"));
        }
    }

    /** 2. 高风险突增：当日 HIGH 风险事件数达到阈值（疑似敏感信息批量泄露） */
    private void detectRiskSpike(String day, List<Map<String, Object>> anomalies) {
        final long threshold = 10L;
        Long high = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gateway_audit_event WHERE timestamp >= ? AND input_risk_level = 'HIGH'",
                Long.class, day);
        long v = high != null ? high : 0L;
        if (v >= threshold) {
            anomalies.add(build("HIGH", "RISK_SPIKE", "高风险事件突增",
                    "当日检测到 " + v + " 条高风险输入事件，达到告警阈值 " + threshold
                            + "，建议核查脱敏策略与阻断规则配置。",
                    v, "当日"));
        }
    }

    /** 3. 阻断突增：当日 BLOCK 决策数量达到阈值 */
    private void detectBlockSpike(String day, List<Map<String, Object>> anomalies) {
        final long threshold = 10L;
        Long block = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gateway_audit_event WHERE timestamp >= ? AND decision_action = 'BLOCK'",
                Long.class, day);
        long v = block != null ? block : 0L;
        if (v >= threshold) {
            anomalies.add(build("MEDIUM", "BLOCK_SPIKE", "阻断事件偏多",
                    "当日阻断 " + v + " 次调用，达到阈值 " + threshold + "，存在自动化攻击或策略过严的可能。",
                    v, "当日"));
        }
    }

    /** 4. 未知平台：无法归一到标准平台的调用量达到阈值（疑似代理/影子 IT） */
    private void detectUnknownProvider(String day, List<Map<String, Object>> anomalies) {
        final long threshold = 5L;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT target_provider, COUNT(*) AS cnt FROM gateway_audit_event "
                        + "WHERE timestamp >= ? AND target_provider IS NOT NULL "
                        + "GROUP BY target_provider",
                day);
        for (Map<String, Object> row : rows) {
            String raw = String.valueOf(row.get("target_provider"));
            long cnt = ((Number) row.get("cnt")).longValue();
            var info = com.hdu.apisensitivities.utils.ProviderNormalizer.normalize(raw);
            if (info.code().equals(com.hdu.apisensitivities.utils.ProviderNormalizer.UNKNOWN.code())
                    && cnt >= threshold) {
                anomalies.add(build("MEDIUM", "UNKNOWN_PROVIDER", "未识别 LLM 平台",
                        "存在 " + cnt + " 次调用指向未登记的外部平台（原始标识: " + raw
                                + "），建议核查是否属于企业允许名单。",
                        cnt, "当日"));
            }
        }
    }

    private Map<String, Object> build(String level, String type, String title, String detail, long count,
            String timeWindow) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", type + "-" + LocalDateTime.now().format(FMT));
        m.put("level", level);
        m.put("type", type);
        m.put("title", title);
        m.put("detail", detail);
        m.put("count", count);
        m.put("timeWindow", timeWindow);
        m.put("generatedAt", LocalDateTime.now().format(FMT));
        return m;
    }

    /** 员工标识脱敏：仅保留前缀，满足隐私合规（不展示完整员工账号） */
    private String maskUserId(String userId) {
        if (userId == null || userId.length() <= 2) {
            return "***";
        }
        return userId.substring(0, 2) + "***";
    }

    private int levelWeight(String level) {
        return switch (level == null ? "" : level) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }
}
