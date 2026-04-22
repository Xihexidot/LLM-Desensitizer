package com.hdu.apisensitivities.service.SensitiveDetection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 扫描器智能体：负责语义识别与安全反思
 */
@Slf4j
@Service
public class NlpScanner {

    @Autowired
    private RestTemplate restTemplate;

    private final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private final String MODEL_NAME = "qwen:1.8b";
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 核心功能 1：命名实体识别 (NER)
     * 让 Agent 找出文本中的敏感实体
     */
    public List<String> extractEntities(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String prompt = "你是一个数据安全专家。请从以下文本中识别出所有人名、公司名或项目名称。" +
                "仅返回实体名称，用中文逗号分隔。如果没有，回答'无'。内容：\n" + text;

        Map<String, Object> request = new HashMap<>();
        request.put("model", MODEL_NAME);
        request.put("prompt", prompt);
        request.put("stream", false);

        try {
            String jsonResponse = restTemplate.postForObject(OLLAMA_URL, request, String.class);
            JsonNode root = objectMapper.readTree(jsonResponse);
            String aiResult = root.get("response").asText();

            return processRawAiString(aiResult);
        } catch (Exception e) {
            log.error("AI 实体识别失败，原因: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 核心功能 2：安全反思 (Self-Reflection)
     * 检查脱敏后的结果是否依然存在风险
     */
    public boolean checkSafety(String maskedText) {
        String prompt = "你是一个安全审计专家。检查以下经过脱敏处理的文本（注意：[ENTITY_n] 格式是安全的占位符）。" +
                "如果文中仍残留真实完整的人名、具体的公司名或机密信息，请回答'危险'，否则回答'安全'。内容：\n" + maskedText;

        Map<String, Object> request = new HashMap<>();
        request.put("model", MODEL_NAME);
        request.put("prompt", prompt);
        request.put("stream", false);

        try {
            String jsonResponse = restTemplate.postForObject(OLLAMA_URL, request, String.class);
            JsonNode root = objectMapper.readTree(jsonResponse);
            String aiResult = root.get("response").asText().trim();

            log.info("Agent 反思结论: {}", aiResult);
            return aiResult.contains("危险");
        } catch (Exception e) {
            log.error("Agent 反思过程出错，默认判定为安全: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 辅助工具：将 AI 返回的杂乱字符串清洗为 List
     */
    private List<String> processRawAiString(String raw) {
        if (raw == null || raw.contains("无") || raw.length() < 2) {
            return Collections.emptyList();
        }

        // 处理中文逗号、换行符，并去重
        return Arrays.stream(raw.replace("，", ",").split(","))
                .map(String::trim)
                .filter(s -> s.length() >= 2) // 过滤掉单字干扰
                .distinct()
                .collect(Collectors.toList());
    }
}
