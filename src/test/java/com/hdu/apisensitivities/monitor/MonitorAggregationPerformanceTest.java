package com.hdu.apisensitivities.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdu.apisensitivities.service.monitor.MonitorAnomalyService;
import com.hdu.apisensitivities.service.monitor.MonitorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 监控聚合链路性能测试。
 * <p>
 * 模拟比赛现场看板查询场景，验证概览、分平台、趋势和异常分析在千级审计数据下的响应能力。
 * </p>
 */
@SpringBootTest
@Transactional
class MonitorAggregationPerformanceTest {

    private static final String LOG_DIR = "./logs";
    private static final int ROWS = 1200;
    private static final int ROUNDS = 20;
    private static final int WARMUP_ROUNDS = 3;
    private static final String[] PROVIDERS = {
            "OpenAI", "Claude", "DeepSeek", "通义千问", "文心一言", "豆包",
            "Kimi", "混元", "Gemini", "Perplexity", "Ollama", "unknown-vendor"
    };

    @Autowired
    private MonitorService monitorService;

    @Autowired
    private MonitorAnomalyService anomalyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void benchmarkAggregationApis() throws Exception {
        seedRows();
        String day = LocalDate.now().toString();

        Runnable warmup = () -> {
            monitorService.getOverview(day);
            monitorService.groupByStandardProvider(day);
            monitorService.getTrend(day, 24);
            anomalyService.detect(day);
        };
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            warmup.run();
        }

        Metrics overview = measure(() -> monitorService.getOverview(day));
        Metrics byProvider = measure(() -> monitorService.groupByStandardProvider(day));
        Metrics trend = measure(() -> monitorService.getTrend(day, 24));
        Metrics anomalies = measure(() -> anomalyService.detect(day));

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path logPath = preparePath("monitor_perf_result_" + timestamp + ".log");
        Path jsonPath = preparePath("monitor_perf_detail_" + timestamp + ".json");

        String report = buildReport(overview, byProvider, trend, anomalies);
        Files.writeString(logPath, report, StandardCharsets.UTF_8);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("seedRows", ROWS);
        detail.put("rounds", ROUNDS);
        detail.put("overview", overview.toMap());
        detail.put("byProvider", byProvider.toMap());
        detail.put("trend", trend.toMap());
        detail.put("anomalies", anomalies.toMap());
        Files.writeString(jsonPath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(detail),
                StandardCharsets.UTF_8);

        System.out.println(report);
        System.out.println("报告已保存: " + logPath.toAbsolutePath());
        System.out.println("明细已保存: " + jsonPath.toAbsolutePath());

        assertTrue(overview.p95() < 1500, "overview P95 超出预算: " + overview.p95() + "ms");
        assertTrue(byProvider.p95() < 1500, "byProvider P95 超出预算: " + byProvider.p95() + "ms");
        assertTrue(trend.p95() < 1500, "trend P95 超出预算: " + trend.p95() + "ms");
        assertTrue(anomalies.p95() < 1500, "anomalies P95 超出预算: " + anomalies.p95() + "ms");
    }

    private Metrics measure(Runnable action) {
        List<Long> costs = new ArrayList<>(ROUNDS);
        for (int i = 0; i < ROUNDS; i++) {
            long start = System.nanoTime();
            action.run();
            costs.add((System.nanoTime() - start) / 1_000_000);
        }
        return Metrics.of(costs);
    }

    private void seedRows() {
        String sql = "INSERT INTO gateway_audit_event (event_id, timestamp, user_id, department, channel, "
                + "target_provider, decision_action, input_risk_level) VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?)";
        for (int i = 0; i < ROWS; i++) {
            String provider = PROVIDERS[i % PROVIDERS.length];
            String channel = (i % 2 == 0) ? "BROWSER_PLUGIN" : "backend-api";
            String risk = (i % 5 == 0) ? "HIGH" : ((i % 3 == 0) ? "MEDIUM" : "LOW");
            String decision = (i % 7 == 0) ? "BLOCK" : "ALLOW";
            jdbcTemplate.update(sql, "perf-monitor-" + i, "u-perf-" + (i % 20), "压测部",
                    channel, provider, decision, risk);
        }
    }

    private Path preparePath(String fileName) throws Exception {
        Path path = Paths.get(LOG_DIR, fileName);
        Files.createDirectories(path.getParent());
        return path;
    }

    private String buildReport(Metrics overview, Metrics byProvider, Metrics trend, Metrics anomalies) {
        return "\n" +
                "================================================================================\n" +
                "【监控聚合链路性能测试报告】\n" +
                "================================================================================\n" +
                "测试时间            : " + LocalDateTime.now() + "\n" +
                "审计样本规模        : " + ROWS + " 条\n" +
                "单接口测量轮次      : " + ROUNDS + " 轮\n" +
                "overview(ms)        : avg=" + formatDouble(overview.avg()) +
                "  p95=" + overview.p95() + "  max=" + overview.max() + "\n" +
                "byProvider(ms)      : avg=" + formatDouble(byProvider.avg()) +
                "  p95=" + byProvider.p95() + "  max=" + byProvider.max() + "\n" +
                "trend(ms)           : avg=" + formatDouble(trend.avg()) +
                "  p95=" + trend.p95() + "  max=" + trend.max() + "\n" +
                "anomalies(ms)       : avg=" + formatDouble(anomalies.avg()) +
                "  p95=" + anomalies.p95() + "  max=" + anomalies.max() + "\n" +
                "================================================================================\n";
    }

    private String formatDouble(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private record Metrics(List<Long> sortedValues, double avg) {

        static Metrics of(List<Long> values) {
            List<Long> sorted = values.stream().sorted().toList();
            double avgValue = values.stream().mapToLong(Long::longValue).average().orElse(0);
            return new Metrics(sorted, avgValue);
        }

        long p95() {
            int index = (int) Math.ceil(sortedValues.size() * 0.95) - 1;
            int safeIndex = Math.max(0, Math.min(index, sortedValues.size() - 1));
            return sortedValues.get(safeIndex);
        }

        long max() {
            return sortedValues.get(sortedValues.size() - 1);
        }

        Map<String, Object> toMap() {
            return Map.of(
                    "avgMs", avg(),
                    "p95Ms", p95(),
                    "maxMs", max());
        }
    }
}
