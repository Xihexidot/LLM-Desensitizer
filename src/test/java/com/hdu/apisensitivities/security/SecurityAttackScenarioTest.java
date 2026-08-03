package com.hdu.apisensitivities.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.service.DesensitizationManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网络安全攻防场景专项测试。
 * <p>
 * 数据源：{@code security_attack_test_set.json}（从数据泄露、越权访问、注入攻击、XSS、
 * 绕过检测等真实攻防案例提炼）。对每条用例执行三项安全断言：
 * <ul>
 * <li><b>数据泄露防护</b>：secrets 中所有敏感明文（含空格/连字符紧凑形式）不得残留在脱敏输出；</li>
 * <li><b>类型识别</b>：expected_types 中的敏感类型必须被正确识别；</li>
 * <li><b>防过度脱敏</b>：preserve 中的业务文本（如 SQL/提示注入载荷结构）必须原样保留。</li>
 * </ul>
 * </p>
 */
@SpringBootTest
class SecurityAttackScenarioTest {

    @Autowired
    private DesensitizationManager desensitizationManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void attackScenarios_noDataLeak_andTypeRecognition() throws Exception {
        List<Map<String, Object>> cases = loadCases();
        assertFalse(cases.isEmpty(), "攻防测试集为空");

        Map<String, Integer> categoryStats = new LinkedHashMap<>();
        int leakFree = 0;
        int recognized = 0;
        int preserved = 0;

        for (Map<String, Object> c : cases) {
            String id = (String) c.get("id");
            String category = (String) c.getOrDefault("category", "UNKNOWN");
            String content = (String) c.get("content");
            String language = (String) c.getOrDefault("language", "zh");
            List<String> secrets = castStringList(c.get("secrets"));
            List<String> expectedTypes = castStringList(c.get("expected_types"));
            List<String> preserve = castStringList(c.get("preserve"));

            DesensitizationResponse resp = desensitizationManager.process(DesensitizationRequest.builder()
                    .content(content)
                    .language(language)
                    .dataType("TEXT")
                    .sessionId("sec-" + id)
                    .build());

            String out = resp.getDesensitizedContent() == null ? "" : resp.getDesensitizedContent();
            String outCompact = out.replaceAll("\\s+", "");

            Set<String> detectedTypes = new HashSet<>();
            if (resp.getDetectedEntities() != null) {
                for (SensitiveEntity e : resp.getDetectedEntities()) {
                    if (e.getType() != null) {
                        detectedTypes.add(e.getType().name());
                    }
                }
            }

            // 1) 数据泄露防护：任何 secret 明文不得残留在脱敏输出
            StringBuilder leaked = new StringBuilder();
            for (String s : secrets) {
                String compact = s.replaceAll("\\s+", "");
                if (out.contains(s) || outCompact.contains(compact)) {
                    leaked.append(" [").append(s).append("]");
                }
            }
            // 1.1) 高敏完全掩码 secret 片段级断言：任意 >=4 字符连续片段不得残留（防止"Admin@"式部分泄露）
            List<String> highMaskSecrets = castStringList(c.get("high_mask_secrets"));
            for (String s : highMaskSecrets) {
                for (String run : extractRuns(s)) {
                    if (out.contains(run) || outCompact.contains(run)) {
                        leaked.append(" [").append(s).append(" 残留片段:").append(run).append("]");
                    }
                }
            }
            assertTrue(leaked.length() == 0,
                    "[" + id + "] 数据泄露：敏感明文仍残留在脱敏输出 ->" + leaked);
            leakFree++;

            // 2) 类型识别：期望类型必须被识别到
            List<String> missing = new ArrayList<>();
            for (String t : expectedTypes) {
                if (!detectedTypes.contains(t)) {
                    missing.add(t);
                }
            }
            assertTrue(missing.isEmpty(),
                    "[" + id + "] 未识别到期望敏感类型 " + missing + "，实际识别=" + detectedTypes);
            recognized++;

            // 3) 防过度脱敏：业务文本（注入载荷等）必须保留
            for (String p : preserve) {
                assertTrue(out.contains(p), "[" + id + "] 业务文本被误伤：" + p);
            }
            if (!preserve.isEmpty()) {
                preserved++;
            }

            categoryStats.merge(category, 1, Integer::sum);
        }

        System.out.println("\n========== 网络安全攻防场景专项测试 ==========");
        System.out.println("用例总数: " + cases.size() + "，无泄露通过: " + leakFree
                + "，类型识别通过: " + recognized + "，防过度脱敏通过: " + preserved);
        System.out.println("分类分布: " + categoryStats);
        System.out.println("=============================================");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadCases() throws Exception {
        ClassPathResource resource = new ClassPathResource("security_attack_test_set.json");
        Map<String, Object> root = objectMapper.readValue(resource.getInputStream(), Map.class);
        Object cases = root.get("cases");
        return cases instanceof List ? (List<Map<String, Object>>) cases : List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> castStringList(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object o : (List<Object>) value) {
            if (o != null) {
                result.add(String.valueOf(o));
            }
        }
        return result;
    }

    /** 提取敏感串中的连续字母数字片段（>=4 字符），用于检测"部分泄露"残留 */
    private List<String> extractRuns(String secret) {
        List<String> runs = new ArrayList<>();
        Matcher matcher = RUN_PATTERN.matcher(secret);
        while (matcher.find()) {
            runs.add(matcher.group());
        }
        return runs;
    }

    private static final Pattern RUN_PATTERN = Pattern.compile("[A-Za-z0-9]{4,}");
}
