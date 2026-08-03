package com.hdu.apisensitivities.service.desensitization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.DesensitizationManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分级分类脱敏全链路集成验证测试。
 * <p>
 * 基于 {@code my_pii_test_set.json} 真实业务样本，度量两项业务指标：
 * <ul>
 * <li><b>识别覆盖率（Recall）</b> = 正确识别出的期望实体数 / 期望实体总数</li>
 * <li><b>脱敏准确率（Mask Accuracy）</b> = 已被消除明文（原文不再残留）的实体数 / 正确识别实体数</li>
 * </ul>
 * 业务标准：识别覆盖率 ≥ 0.80、脱敏准确率 ≥ 0.90。
 * </p>
 */
@SpringBootTest
class GradedFlowVerificationTest {

    private static final int SAMPLE_LIMIT = 60;
    private static final double RECALL_TARGET = 0.80;
    private static final double ACCURACY_TARGET = 0.90;

    @Autowired
    private DesensitizationManager desensitizationManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void gradedDesensitizationMeetsBusinessMetrics() throws Exception {
        List<Map<String, Object>> samples = loadSamples();
        assertTrue(samples.size() >= SAMPLE_LIMIT, "数据集样本不足");

        int totalExpected = 0;
        int totalMatched = 0;
        int totalMatchedAndMasked = 0;
        int totalMasked = 0;
        Map<String, int[]> typeStats = new LinkedHashMap<>();

        int processed = 0;
        for (Map<String, Object> sample : samples) {
            if (processed >= SAMPLE_LIMIT) {
                break;
            }
            processed++;

            String content = (String) sample.get("content");
            String language = (String) sample.getOrDefault("language", "zh");
            List<Map<String, Object>> expectedList = castList(sample.get("expected_entities"));

            List<SensitiveEntity> expected = new ArrayList<>();
            for (Map<String, Object> exp : expectedList) {
                String typeStr = (String) exp.get("type");
                String text = (String) exp.get("text");
                SensitiveType type = mapType(typeStr);
                if (type == null) {
                    continue;
                }
                expected.add(SensitiveEntity.builder()
                        .type(type)
                        .originalText(text)
                        .start(-1)
                        .end(-1)
                        .confidence(1.0)
                        .build());
            }

            DesensitizationRequest request = DesensitizationRequest.builder()
                    .content(content)
                    .language(language)
                    .dataType("TEXT")
                    .sessionId("verify-" + processed)
                    .build();
            DesensitizationResponse response = desensitizationManager.process(request);

            List<SensitiveEntity> detected = response.getDetectedEntities() == null
                    ? List.of()
                    : response.getDetectedEntities();
            String desensitized = response.getDesensitizedContent() == null ? "" : response.getDesensitizedContent();
            String desensitizedCompact = desensitized.replaceAll("\\s+", "");

            // 识别覆盖率：期望实体在检测结果中可匹配（类型一致 + 文本包含）
            for (SensitiveEntity exp : expected) {
                totalExpected++;
                int[] stat = typeStats.computeIfAbsent(exp.getType().name(), k -> new int[] { 0, 0, 0 });
                stat[0]++; // 期望数

                boolean matched = detected.stream().anyMatch(d -> d.getType() == exp.getType()
                        && containsText(d.getOriginalText(), exp.getOriginalText()));

                // 脱敏准确率：原文（去空白）不再残留在脱敏结果中
                String expectedCompact = exp.getOriginalText().replaceAll("\\s+", "");
                boolean masked = !desensitizedCompact.contains(expectedCompact);

                if (matched) {
                    totalMatched++;
                    stat[1]++; // 识别成功数
                    if (masked) {
                        totalMatchedAndMasked++;
                    }
                }
                if (masked) {
                    totalMasked++;
                    stat[2]++; // 已消除明文数
                }
            }
        }

        double recall = totalExpected == 0 ? 1.0 : (double) totalMatched / totalExpected;
        double accuracy = totalMatched == 0 ? 1.0 : (double) totalMatchedAndMasked / totalMatched;

        System.out.println("\n========== 分级分类脱敏全链路指标 ==========");
        System.out.println("样本数: " + processed + "，期望实体总数: " + totalExpected
                + "，识别成功: " + totalMatched + "，识别且明文已消除: " + totalMatchedAndMasked
                + "（任意明文已消除: " + totalMasked + "）");
        System.out.printf("识别覆盖率(Recall): %.2f%%（目标 ≥ %.0f%%）%n", recall * 100, RECALL_TARGET * 100);
        System.out.printf("脱敏准确率(Mask Accuracy): %.2f%%（目标 ≥ %.0f%%）%n", accuracy * 100, ACCURACY_TARGET * 100);
        System.out.println("分类型明细 (期望/识别/消除):");
        typeStats.forEach((type, stat) -> System.out.printf("  %-14s %d / %d / %d%n", type, stat[0], stat[1], stat[2]));
        System.out.println("============================================");

        assertTrue(recall >= RECALL_TARGET,
                String.format("识别覆盖率 %.2f%% 未达业务标准 %.0f%%", recall * 100, RECALL_TARGET * 100));
        assertTrue(accuracy >= ACCURACY_TARGET,
                String.format("脱敏准确率 %.2f%% 未达业务标准 %.0f%%", accuracy * 100, ACCURACY_TARGET * 100));
    }

    private List<Map<String, Object>> loadSamples() throws Exception {
        ClassPathResource resource = new ClassPathResource("my_pii_test_set.json");
        return objectMapper.readValue(resource.getInputStream(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
    }

    private boolean containsText(String detectedText, String expectedText) {
        if (detectedText == null || expectedText == null) {
            return false;
        }
        String a = detectedText.replaceAll("\\s+", "");
        String b = expectedText.replaceAll("\\s+", "");
        return a.contains(b) || b.contains(a);
    }

    private SensitiveType mapType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "person" -> SensitiveType.PERSON;
            case "phone" -> SensitiveType.PHONE_NUMBER;
            case "id_number" -> SensitiveType.ID_CARD;
            case "bank_card" -> SensitiveType.BANK_CARD;
            case "address" -> SensitiveType.ADDRESS;
            case "email" -> SensitiveType.EMAIL;
            case "license_plate" -> SensitiveType.LICENSE_PLATE;
            case "passport" -> SensitiveType.PASSPORT;
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }
}
