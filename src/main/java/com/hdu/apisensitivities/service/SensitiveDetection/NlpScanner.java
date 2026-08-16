package com.hdu.apisensitivities.service.SensitiveDetection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class NlpScanner {

    @Autowired
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${local.agent.enabled:true}")
    private boolean enabled;

    @Value("${local.agent.url:http://127.0.0.1:11434/api/generate}")
    private String agentUrl;

    @Value("${local.agent.health-url:http://127.0.0.1:11434/api/tags}")
    private String healthUrl;

    @Value("${local.agent.mode:OLLAMA_LOCAL}")
    private String agentMode;

    @Value("${local.agent.model:deepseek-r1:1.5b}")
    private String modelName;

    public record AgentStatus(boolean enabled, boolean reachable, String mode, String endpoint, String model,
            String message) {
    }

    public AgentStatus getStatus() {
        if (!enabled) {
            return new AgentStatus(false, false, agentMode, agentUrl, modelName, "本地/远程 Agent 增强未启用");
        }

        try {
            restTemplate.getForObject(healthUrl, String.class);
            return new AgentStatus(true, true, agentMode, agentUrl, modelName, "Agent 服务可访问");
        } catch (Exception e) {
            log.warn("Agent 探活失败: {}", e.getMessage());
            return new AgentStatus(true, false, agentMode, agentUrl, modelName, e.getMessage());
        }
    }

    /**
     * 核心功能 1：命名实体识别 (NER)
     * 让 Agent 找出文本中的敏感实体
     */
    public List<String> extractEntities(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        AgentStatus status = getStatus();
        if (!status.reachable()) {
            log.info("跳过 Agent 语义提取，状态: {}", status.message());
            return Collections.emptyList();
        }

        String prompt = "你是一个数据安全专家。请从以下文本中识别出所有人名、公司名或项目名称。" +
                "仅返回实体名称，用中文逗号分隔。如果没有，回答'无'。内容：\n" + text;

        Map<String, Object> request = new HashMap<>();
        request.put("model", this.modelName); // 💡 使用动态识别的模型名
        request.put("prompt", prompt);
        request.put("stream", false);

        try {
            log.info("Agent 正在提取实体，模式: {}, 模型: {}, 地址: {}", this.agentMode, this.modelName, this.agentUrl);
            String jsonResponse = restTemplate.postForObject(agentUrl, request, String.class);
            JsonNode root = objectMapper.readTree(jsonResponse);
            String aiResult = root.path("response").asText("");

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

        AgentStatus status = getStatus();
        if (!status.reachable()) {
            log.info("跳过 Agent 安全复检，状态: {}", status.message());
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
            log.debug("正在向 Agent 发送反思请求，模式: {}, 模型: {}", this.agentMode, this.modelName);
            String jsonResponse = restTemplate.postForObject(agentUrl, request, String.class);
            JsonNode root = objectMapper.readTree(jsonResponse);
            String aiResult = root.path("response").asText("").trim();

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
