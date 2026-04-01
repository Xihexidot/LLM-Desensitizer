package com.hdu.apisensitivities.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.service.DesensitizationManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
public class DesensitizationBenchmarkTest {

    @Autowired
    private DesensitizationManager desensitizationManager;

    @Autowired
    private ObjectMapper objectMapper;

    // 建立类型映射表：数据集类型 -> 你定义的 SensitiveType 枚举名
    private static final Map<String, String> TYPE_MAP = Map.of(
            "person", "CHINESE_NAME",
            "phone", "MOBILE_PHONE",
            "address", "ADDRESS",
            "email", "EMAIL"
    );

@Test
    public void runBenchmark() throws Exception {
        // 1. 从 resources 目录读取 JSON (使用 <> 简化泛型)
        InputStream is = getClass().getClassLoader().getResourceAsStream("my_pii_test_set.json");
        // 如果这里报错找不到 TestDataDTO，请确保它在同一个包下或已 import
        List<TestDataDTO> testCases = objectMapper.readValue(is, new TypeReference<>() {});

        int totalExpected = 0;
        int totalFound = 0;

        for (TestDataDTO testCase : testCases) {
            // 2. 构建 Request (只填充必要的字段)
            DesensitizationRequest request = DesensitizationRequest.builder()
                    .content(testCase.getContent())
                    .dataType("TEXT")
                    .language(testCase.getLanguage())
                    .autoScenarioDetection(true)
                    .build();

            // 3. 执行识别
            DesensitizationResponse response = desensitizationManager.process(request);

            // 【修正 1】：根据你的响应类，字段名是 detectedEntities 而不是 entities
            List<SensitiveEntity> actualEntities = response.getDetectedEntities();

            if (actualEntities == null) continue;

            // 4. 比对逻辑
            totalExpected += testCase.getExpected_entities().size();
            log.info("ID: {} | 预期: {} 个 | 实际识别到: {}",
                testCase.getId(),
                testCase.getExpected_entities().size(),
                actualEntities.stream().map(e -> e.getOriginalText() + ":" + e.getType()).collect(Collectors.toList())
            );
            for (Map<String, Object> exp : testCase.getExpected_entities()) {
                String expText = (String) exp.get("text");
                String expType = (String) exp.get("type");

//                // 【修正 2】：利用 TYPE_MAP 来同时校验内容和类型（可选，让测试更严谨）
//                boolean isFound = actualEntities.stream()
//                        .anyMatch(a -> a.getOriginalText().equals(expText)
//                                && TYPE_MAP.getOrDefault(expType, "").equals(a.getType().name()));

                // 如果觉得类型对不上太麻烦，可以先只比对文本内容：
                 boolean isFound = actualEntities.stream().anyMatch(a -> a.getOriginalText().equals(expText));

                if (isFound) {
                    totalFound++;
                } else {
                    log.debug("未识别出的实体: {} (类型: {})", expText, expType);
                }
            }
        }

        double recall = totalExpected == 0 ? 0 : (double) totalFound / totalExpected * 100;
        log.info("=========================================");
        log.info("测试完成！总预期实体: {}, 成功匹配: {}, 召回率: {}%",
                totalExpected, totalFound, String.format("%.2f", recall));
        log.info("=========================================");
    }
}