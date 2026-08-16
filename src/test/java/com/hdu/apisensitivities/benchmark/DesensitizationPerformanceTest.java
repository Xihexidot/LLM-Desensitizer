package com.hdu.apisensitivities.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.OperatingSystemMXBean;
import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.service.DesensitizationManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏主链路性能基线测试。
 * <p>
 * 该测试聚焦两类最核心的比赛场景：
 * 1. 短文本高频请求（客服/办公输入即刻脱敏）
 * 2. 长文本批量脱敏（日志、工单、报告类文本）
 * </p>
 * <p>
 * 输出结果会写入 logs 目录，便于直接纳入比赛报告。
 * </p>
 */
@SpringBootTest
class DesensitizationPerformanceTest {

    private static final String LOG_DIR = "./logs";
    private static final String SHORT_TEXT_DATASET = "my_pii_test_set.json";
    private static final String LONG_TEXT_DATASET = "static/sample_sensitive_30k.txt";
    private static final int SHORT_CASE_LIMIT = 500;
    private static final int SHORT_ROUNDS = 20;
    private static final int LONG_ROUNDS = 10;
    private static final int WARMUP_SHORT_CASES = 50;
    private static final int WARMUP_LONG_ROUNDS = 2;

    @Autowired
    private DesensitizationManager desensitizationManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void benchmarkCorePipeline() throws Exception {
        List<TestDataDTO> shortCases = loadShortCases();
        String longText = loadLongText();

        warmup(shortCases, longText);

        RuntimeSnapshot before = RuntimeSnapshot.capture();
        long wallStart = System.nanoTime();
        List<Long> shortUs = measureShortTexts(shortCases);
        List<Long> longMs = measureLongText(longText);
        long wallNanos = System.nanoTime() - wallStart;
        RuntimeMetrics runtimeMetrics = RuntimeMetrics.from(before, RuntimeSnapshot.capture(), wallNanos);

        Metrics shortMetrics = Metrics.of(shortUs);
        Metrics longMetrics = Metrics.of(longMs);
        double throughputCharsPerSec = longText.length() * 1000.0 / longMetrics.avg();

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path logPath = preparePath("perf_result_" + timestamp + ".log");
        Path jsonPath = preparePath("perf_detail_" + timestamp + ".json");

        String report = buildReport(shortMetrics, longMetrics, runtimeMetrics, throughputCharsPerSec,
                longText.length());
        Files.writeString(logPath, report, StandardCharsets.UTF_8);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("throughputCharsPerSec", throughputCharsPerSec);
        detail.put("longText", Map.of(
                "chars", longText.length(),
                "rounds", LONG_ROUNDS,
                "avgMs", longMetrics.avg(),
                "maxMs", longMetrics.max()));
        detail.put("shortText", Map.of(
                "calls", shortUs.size(),
                "avgUs", shortMetrics.avg(),
                "p50Us", shortMetrics.p50(),
                "p95Us", shortMetrics.p95(),
                "p99Us", shortMetrics.p99(),
                "maxUs", shortMetrics.max()));
        detail.put("runtime", runtimeMetrics.toMap());
        Files.writeString(jsonPath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(detail),
                StandardCharsets.UTF_8);

        System.out.println(report);
        System.out.println("报告已保存: " + logPath.toAbsolutePath());
        System.out.println("明细已保存: " + jsonPath.toAbsolutePath());

        assertTrue(shortMetrics.avg() < 5000, "短文本平均耗时超出预算: " + shortMetrics.avg() + "us");
        assertTrue(shortMetrics.p95() < 10000, "短文本 P95 超出预算: " + shortMetrics.p95() + "us");
        assertTrue(longMetrics.avg() < 200, "长文本平均耗时超出预算: " + longMetrics.avg() + "ms");
        assertTrue(throughputCharsPerSec > 30000, "吞吐量低于预算: " + throughputCharsPerSec + " chars/s");
    }

    private void warmup(List<TestDataDTO> shortCases, String longText) {
        int warmupSize = Math.min(WARMUP_SHORT_CASES, shortCases.size());
        for (int i = 0; i < warmupSize; i++) {
            desensitizationManager.process(buildRequest(shortCases.get(i).getContent()));
        }
        for (int i = 0; i < WARMUP_LONG_ROUNDS; i++) {
            desensitizationManager.process(buildRequest(longText));
        }
    }

    private List<Long> measureShortTexts(List<TestDataDTO> shortCases) {
        List<Long> costs = new ArrayList<>(shortCases.size() * SHORT_ROUNDS);
        for (int round = 0; round < SHORT_ROUNDS; round++) {
            for (TestDataDTO testCase : shortCases) {
                long start = System.nanoTime();
                desensitizationManager.process(buildRequest(testCase.getContent()));
                costs.add((System.nanoTime() - start) / 1_000);
            }
        }
        return costs;
    }

    private List<Long> measureLongText(String longText) {
        List<Long> costs = new ArrayList<>(LONG_ROUNDS);
        for (int round = 0; round < LONG_ROUNDS; round++) {
            long start = System.nanoTime();
            desensitizationManager.process(buildRequest(longText));
            costs.add((System.nanoTime() - start) / 1_000_000);
        }
        return costs;
    }

    private DesensitizationRequest buildRequest(String content) {
        return DesensitizationRequest.builder()
                .content(content)
                .dataType("TEXT")
                .language("mixed")
                .strictMode(true)
                .autoScenarioDetection(false)
                .preserveStructure(true)
                .build();
    }

    private List<TestDataDTO> loadShortCases() throws Exception {
        try (InputStream inputStream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(SHORT_TEXT_DATASET),
                "找不到短文本数据集: " + SHORT_TEXT_DATASET)) {
            List<TestDataDTO> allCases = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            return allCases.stream().limit(SHORT_CASE_LIMIT).toList();
        }
    }

    private String loadLongText() throws Exception {
        try (InputStream inputStream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(LONG_TEXT_DATASET),
                "找不到长文本样本: " + LONG_TEXT_DATASET)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private Path preparePath(String fileName) throws Exception {
        Path path = Paths.get(LOG_DIR, fileName);
        Files.createDirectories(path.getParent());
        return path;
    }

    private String buildReport(Metrics shortMetrics, Metrics longMetrics, RuntimeMetrics runtimeMetrics,
            double throughputCharsPerSec, int longChars) {
        return "\n" +
                "================================================================================\n" +
                "【脱敏主链路性能测试报告】\n" +
                "================================================================================\n" +
                "测试时间       : " + LocalDateTime.now() + "\n" +
                "短文本用例数   : " + SHORT_CASE_LIMIT + " 条 × " + SHORT_ROUNDS + " 轮 = "
                + (SHORT_CASE_LIMIT * SHORT_ROUNDS) + " 次调用\n" +
                "短文本耗时(us) : avg=" + formatDouble(shortMetrics.avg()) +
                "  p50=" + shortMetrics.p50() +
                "  p95=" + shortMetrics.p95() +
                "  p99=" + shortMetrics.p99() +
                "  max=" + shortMetrics.max() + "\n" +
                "长文本样本     : " + longChars + " chars × " + LONG_ROUNDS + " 轮\n" +
                "长文本耗时(ms) : avg=" + formatDouble(longMetrics.avg()) +
                "  max=" + longMetrics.max() + "\n" +
                "吞吐量         : " + formatDouble(throughputCharsPerSec) + " 字符/秒\n" +
                "进程CPU占用    : avg=" + formatDouble(runtimeMetrics.cpuUsagePct()) + "%\n" +
                "堆内存变化     : before=" + formatDouble(runtimeMetrics.heapBeforeMb()) +
                "MB  after=" + formatDouble(runtimeMetrics.heapAfterMb()) +
                "MB  delta=" + formatDouble(runtimeMetrics.heapDeltaMb()) + "MB\n" +
                "GC增量         : count=" + runtimeMetrics.gcCountDelta() +
                "  time=" + runtimeMetrics.gcTimeDeltaMs() + "ms\n" +
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

        long p50() {
            return percentile(0.50);
        }

        long p95() {
            return percentile(0.95);
        }

        long p99() {
            return percentile(0.99);
        }

        long max() {
            return sortedValues.get(sortedValues.size() - 1);
        }

        private long percentile(double ratio) {
            int index = (int) Math.ceil(sortedValues.size() * ratio) - 1;
            int safeIndex = Math.max(0, Math.min(index, sortedValues.size() - 1));
            return sortedValues.get(safeIndex);
        }
    }

    private record RuntimeSnapshot(long heapUsedBytes, long processCpuTimeNanos, long gcCount, long gcTimeMs) {

        static RuntimeSnapshot capture() {
            MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long gcCount = 0;
            long gcTimeMs = 0;
            for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gcBean.getCollectionCount() > 0) {
                    gcCount += gcBean.getCollectionCount();
                }
                if (gcBean.getCollectionTime() > 0) {
                    gcTimeMs += gcBean.getCollectionTime();
                }
            }
            return new RuntimeSnapshot(
                    memoryMXBean.getHeapMemoryUsage().getUsed(),
                    osBean.getProcessCpuTime(),
                    gcCount,
                    gcTimeMs);
        }
    }

    private record RuntimeMetrics(double heapBeforeMb, double heapAfterMb, double heapDeltaMb,
            double cpuUsagePct, long gcCountDelta, long gcTimeDeltaMs) {

        static RuntimeMetrics from(RuntimeSnapshot before, RuntimeSnapshot after, long wallNanos) {
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            long cpuNanos = Math.max(0, after.processCpuTimeNanos() - before.processCpuTimeNanos());
            double cpuPct = wallNanos <= 0 ? 0 : cpuNanos * 100.0 / (wallNanos * processors);
            double beforeMb = before.heapUsedBytes() / 1024.0 / 1024.0;
            double afterMb = after.heapUsedBytes() / 1024.0 / 1024.0;
            return new RuntimeMetrics(
                    beforeMb,
                    afterMb,
                    afterMb - beforeMb,
                    cpuPct,
                    Math.max(0, after.gcCount() - before.gcCount()),
                    Math.max(0, after.gcTimeMs() - before.gcTimeMs()));
        }

        Map<String, Object> toMap() {
            return Map.of(
                    "heapBeforeMb", heapBeforeMb,
                    "heapAfterMb", heapAfterMb,
                    "heapDeltaMb", heapDeltaMb,
                    "cpuUsagePct", cpuUsagePct,
                    "gcCountDelta", gcCountDelta,
                    "gcTimeDeltaMs", gcTimeDeltaMs);
        }
    }
}
