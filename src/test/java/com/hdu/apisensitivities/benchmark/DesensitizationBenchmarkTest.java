package com.hdu.apisensitivities.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.DesensitizationManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@SpringBootTest
public class DesensitizationBenchmarkTest {

    private static final String LOG_DIR = "./logs";
    private static final String TEST_DATASET = "my_pii_test_set.json";
    private static final String TEXT_DATA_TYPE = "TEXT";
    private static final boolean AUTO_SCENARIO_DETECTION = false;
    private static final boolean STRICT_MODE = false;
    private static final double BENCHMARK_CONFIDENCE_THRESHOLD = 0.0;

    @Autowired
    private DesensitizationManager desensitizationManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void runBenchmark() throws Exception {
        // 1. 创建本次测试的日志文件
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String logFileName = String.format("benchmark_result_%s.log", timestamp);
        Path logPath = createLogPath(logFileName);

        // 2. 读取测试数据
        List<TestDataDTO> testCases = loadTestCases();

        // 3. 统计变量
        int totalExpected = 0;
        int totalFound = 0;
        int failedCases = 0;
        List<TestCaseResult> caseResults = new ArrayList<>();

        StringBuilder failLog = new StringBuilder();
        failLog.append("失败用例汇总\n").append("==========\n");

        for (TestDataDTO testCase : testCases) {
            TestCaseResult result = new TestCaseResult();
            result.setCaseId(testCase.getId());
            result.setExpectedCount(testCase.getExpected_entities().size());

            // 执行识别
            DesensitizationRequest request = createBenchmarkRequest(testCase);
            DesensitizationResponse response = desensitizationManager.process(request);
            List<SensitiveEntity> actualEntities = getActualEntities(response);

            // 匹配实体
            List<String> unmatchedEntities = new ArrayList<>();
            Set<String> consumedActualKeys = new HashSet<>();
            int matchedCount = 0;

            for (Map<String, Object> exp : testCase.getExpected_entities()) {
                String expText = (String) exp.get("text");
                String expType = (String) exp.get("type");
                // 测试数据使用 code-point 位置（emoji=1），Java 内部使用 UTF-16 char 位置（emoji=2）。
                // 将预期位置从 code-point 转换为 UTF-16 再比较。
                Integer expStart = cpToUtf16(testCase.getContent(), toInteger(exp.get("start")));
                Integer expEnd = cpToUtf16(testCase.getContent(), toInteger(exp.get("end")));
                SensitiveType expectedType = mapDatasetType(expType);

                boolean isFound = actualEntities.stream()
                        .filter(a -> !consumedActualKeys.contains(actualKey(a)))
                        .anyMatch(a -> {
                            if (!a.getOriginalText().equals(expText)) {
                                return false;
                            }
                            if (expectedType != null && a.getType() != expectedType) {
                                return false;
                            }
                            if (expStart != null && expEnd != null
                                    && (a.getStart() != expStart || a.getEnd() != expEnd)) {
                                return false;
                            }
                            return true;
                        });

                if (isFound) {
                    matchedCount++;
                    totalFound++;
                    // 标记实际实体已被消费，防止同一实际实体被重复计数
                    actualEntities.stream()
                            .filter(a -> a.getOriginalText().equals(expText))
                            .findFirst()
                            .ifPresent(a -> consumedActualKeys.add(actualKey(a)));
                } else {
                    unmatchedEntities.add(expText + "(" + expType + ")");
                }
            }

            totalExpected += testCase.getExpected_entities().size();
            result.setMatchedCount(matchedCount);
            result.setUnmatchedEntities(unmatchedEntities);

            double recall = testCase.getExpected_entities().size() == 0 ? 0
                    : (double) matchedCount / testCase.getExpected_entities().size() * 100;
            result.setRecall(recall);
            result.setSuccess(matchedCount == testCase.getExpected_entities().size());

            if (!result.isSuccess()) {
                failedCases++;
                appendFailureLog(failLog, result, testCase);
            }

            caseResults.add(result);
        }

        // 5. 生成详细报告
        StringBuilder report = new StringBuilder();
        report.append("\n").append(repeat("=", 80)).append("\n");
        report.append("【详细测试结果】\n");
        report.append(repeat("=", 80)).append("\n\n");

        for (TestCaseResult result : caseResults) {
            String status = result.isSuccess() ? "✓ 通过" : "✗ 失败";
            String recallStr = String.format("%.2f%%", result.getRecall());

            report.append(String.format("%-20s 预期:%-3d 匹配:%-3d 召回率:%-8s %s\n",
                    result.getCaseId(),
                    result.getExpectedCount(),
                    result.getMatchedCount(),
                    recallStr,
                    status));

            if (!result.getUnmatchedEntities().isEmpty()) {
                report.append(String.format("  └─ 未识别实体: %s\n",
                        String.join(", ", result.getUnmatchedEntities())));
            }
        }

        // 6. 生成统计汇总
        double overallRecall = totalExpected == 0 ? 0 : (double) totalFound / totalExpected * 100;
        double failureRate = testCases.isEmpty() ? 0 : (double) failedCases / testCases.size() * 100;

        report.append("\n").append(repeat("=", 80)).append("\n");
        report.append("【测试统计】\n");
        report.append(repeat("=", 80)).append("\n");
        report.append(String.format("总测试用例数: %d\n", testCases.size()));
        report.append(String.format("失败用例数: %d (%.2f%%)\n", failedCases, failureRate));
        report.append(String.format("总预期实体数: %d\n", totalExpected));
        report.append(String.format("总匹配实体数: %d\n", totalFound));
        report.append(String.format("总体召回率: %.2f%%\n", overallRecall));
        report.append(repeat("=", 80)).append("\n");
        report.append(String.format("日志文件: %s\n", logFileName));
        report.append(repeat("=", 80)).append("\n");

        // 7. 输出到控制台
        System.out.println(report.toString());
        log.info(report.toString());

        // 8. 写入文件
        Files.write(logPath, report.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // 在所有用例处理完之后，写入文件：
        if (failLog.length() > 0) {
            Path failLogPath = Paths.get(LOG_DIR, "failed_cases_" + timestamp + ".log");
            Files.write(failLogPath, failLog.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("失败日志已保存到: " + failLogPath.toAbsolutePath());
        }

        // 9. 同时保存一份 JSON 格式的详细结果（便于后续分析）
        String jsonResult = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(caseResults);
        Path jsonPath = Paths.get(LOG_DIR, String.format("benchmark_detail_%s.json", timestamp));
        Files.write(jsonPath, jsonResult.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("\n✅ 测试完成！");
        System.out.println("📄 详细报告: " + logPath.toAbsolutePath());
        System.out.println("📊 JSON数据: " + jsonPath.toAbsolutePath());
    }

    private Path createLogPath(String logFileName) throws Exception {
        Path logPath = Paths.get(LOG_DIR, logFileName);
        Files.createDirectories(logPath.getParent());
        return logPath;
    }

    private List<TestDataDTO> loadTestCases() throws Exception {
        try (InputStream inputStream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(TEST_DATASET),
                "找不到测试数据文件: " + TEST_DATASET)) {
            return objectMapper.readValue(inputStream, new TypeReference<>() {
            });
        }
    }

    private DesensitizationRequest createBenchmarkRequest(TestDataDTO testCase) {
        return DesensitizationRequest.builder()
                .content(testCase.getContent())
                .dataType(TEXT_DATA_TYPE)
                .language(testCase.getLanguage())
                // 基准测试阶段先固定关闭情景感知，避免干扰规则匹配评估。
                .autoScenarioDetection(AUTO_SCENARIO_DETECTION)
                .strictMode(STRICT_MODE)
                .confidenceThreshold(BENCHMARK_CONFIDENCE_THRESHOLD)
                .blacklist(null)
                .whitelist(null)
                .build();
    }

    private List<SensitiveEntity> getActualEntities(DesensitizationResponse response) {
        List<SensitiveEntity> actualEntities = response.getDetectedEntities();
        if (actualEntities == null) {
            return Collections.emptyList();
        }
        return actualEntities;
    }

    private void appendFailureLog(StringBuilder failLog, TestCaseResult result, TestDataDTO testCase) {
        failLog.append("用例ID: ").append(result.getCaseId()).append("\n");
        failLog.append("  预期实体数: ").append(result.getExpectedCount()).append("\n");
        failLog.append("  匹配实体数: ").append(result.getMatchedCount()).append("\n");
        failLog.append("  未识别实体: ").append(String.join(", ", result.getUnmatchedEntities())).append("\n");
        failLog.append(" 原始文本: ").append(testCase.getContent()).append("\n");
        failLog.append("\n");
    }

    // 辅助方法：重复字符串
    private String repeat(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    /**
     * 将测试数据集中使用的类型字符串映射为 SensitiveType 枚举。
     * 若类型不在已知映射中，返回 null（匹配时仅对比 text，不校验 type）。
     */
    private SensitiveType mapDatasetType(String datasetType) {
        if (datasetType == null)
            return null;
        return switch (datasetType) {
            case "person" -> SensitiveType.PERSON;
            case "phone" -> SensitiveType.PHONE_NUMBER;
            case "id_number" -> SensitiveType.ID_CARD;
            case "bank_card" -> SensitiveType.BANK_CARD;
            case "email" -> SensitiveType.EMAIL;
            case "address" -> SensitiveType.ADDRESS;
            case "license_plate" -> SensitiveType.LICENSE_PLATE;
            case "passport" -> SensitiveType.PASSPORT;
            default -> null;
        };
    }

    private static Integer cpToUtf16(String text, Integer cpIndex) {
        if (text == null || cpIndex == null || cpIndex < 0 || cpIndex > text.codePointCount(0, text.length())) {
            return cpIndex;
        }
        return text.offsetByCodePoints(0, cpIndex);
    }

    private Integer toInteger(Object obj) {
        if (obj instanceof Integer)
            return (Integer) obj;
        if (obj instanceof Number)
            return ((Number) obj).intValue();
        return null;
    }

    private String actualKey(SensitiveEntity entity) {
        return entity.getType().name() + ":" + entity.getStart() + ":" + entity.getEnd() + ":"
                + entity.getOriginalText();
    }

    // 内部类：测试用例结果
    private static class TestCaseResult {
        private String caseId;
        private int expectedCount;
        private int matchedCount;
        private List<String> unmatchedEntities;
        private double recall;
        private boolean success;

        // Getters and Setters
        public String getCaseId() {
            return caseId;
        }

        public void setCaseId(String caseId) {
            this.caseId = caseId;
        }

        public int getExpectedCount() {
            return expectedCount;
        }

        public void setExpectedCount(int expectedCount) {
            this.expectedCount = expectedCount;
        }

        public int getMatchedCount() {
            return matchedCount;
        }

        public void setMatchedCount(int matchedCount) {
            this.matchedCount = matchedCount;
        }

        public List<String> getUnmatchedEntities() {
            return unmatchedEntities;
        }

        public void setUnmatchedEntities(List<String> unmatchedEntities) {
            this.unmatchedEntities = unmatchedEntities;
        }

        public double getRecall() {
            return recall;
        }

        public void setRecall(double recall) {
            this.recall = recall;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }
    }
}
