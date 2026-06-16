package com.hdu.apisensitivities.service.SensitiveDetection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class NlpScanner {

    @Autowired
    private RestTemplate restTemplate;

    private final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== ✨ 核心修改：动态识别模型名 ✨ ====================
    // 从环境变量或配置文件中读取本地模型名称，若未配置则默认降级使用 "qwen:1.8b"
    @Value("${LOCAL_AGENT_MODEL_NAME:qwen:1.8b}")
    private String modelName;
    // =====================================================================

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
        request.put("model", this.modelName); // 💡 使用动态识别的模型名
        request.put("prompt", prompt);
        request.put("stream", false);

        try {
            log.info("Agent 正在提取实体，使用模型: {}", this.modelName);
            String jsonResponse = restTemplate.postForObject(OLLAMA_URL, request, String.class);
            JsonNode root = objectMapper.readTree(jsonResponse);
            String aiResult = root.get("response").asText();

            return processRawAiString(aiResult);
        } catch (Exception e) {
            log.error("❌ AI 实体识别失败，原因: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 核心功能 2：安全反思 (Self-Reflection)
     * 检查脱敏后的结果是否依然存在风险（智能兼容 RAG 自定义 Prompt）
     */
    public boolean checkSafety(String textOrPrompt) {
        if (textOrPrompt == null || textOrPrompt.trim().isEmpty()) {
            return false;
        }

        String finalPrompt;
        // 💡 智能判断：如果传入的内容已经包含了【系统指令】等字样，说明是 LlmProxyService 传过来的 RAG 富 Prompt
        if (textOrPrompt.contains("【系统指令】") || textOrPrompt.contains("【参考合规法规】")) {
            finalPrompt = textOrPrompt; // 直接使用 RAG 拼好的 Prompt
            log.info("本地 Agent 正在基于 RAG 动态知识进行合规审计...");
        } else {
            // 否则，说明是原有的普通无上下文审计，沿用你原本的普通 Prompt 模板
            finalPrompt = "你是一个安全审计专家。检查以下经过脱敏处理的文本（注意：[ENTITY_n] 格式是安全的占位符）。" +
                    "如果文中仍残留真实完整的人名、具体的公司名或机密信息，请回答'危险'，否则回答'安全'。内容：\n" + textOrPrompt;
        }

        Map<String, Object> request = new HashMap<>();
        request.put("model", this.modelName); // 💡 使用动态识别的模型名
        request.put("prompt", finalPrompt);
        request.put("stream", false);

        try {
            log.debug("正在向 Ollama 发送反思请求，模型: {}", this.modelName);
            String jsonResponse = restTemplate.postForObject(OLLAMA_URL, request, String.class);
            JsonNode root = objectMapper.readTree(jsonResponse);
            String aiResult = root.get("response").asText().trim();

            log.info("Agent 审计原始回复: {}", aiResult);
            // 兼容可能出现的英文字样，判定是否包含“危险”或“DANGEROUS”
            return aiResult.contains("危险") || aiResult.toUpperCase().contains("DANGEROUS");
        } catch (Exception e) {
            log.error("❌ Agent 反思过程出错，默认判定为安全（放行）: {}", e.getMessage());
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