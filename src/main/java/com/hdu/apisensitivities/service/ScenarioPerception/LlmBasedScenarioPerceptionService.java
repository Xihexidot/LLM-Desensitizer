package com.hdu.apisensitivities.service.ScenarioPerception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdu.apisensitivities.config.LlmConfig;
import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.LlmProvider;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.LlmClient.LlmClient;
import com.hdu.apisensitivities.service.LlmConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于大模型的情景感知服务
 * 利用LLM强大的语义理解能力来分析用户意图和情景
 */
@Slf4j
@Service("llmScenarioPerceptionService")
public class LlmBasedScenarioPerceptionService implements ScenarioPerceptionService {

    private final Map<String, LlmClient> llmClients;
    private final LlmConfigService llmConfigService;
    private final ObjectMapper objectMapper;

    // 默认使用DeepSeek作为分析模型，因为它通常性价比高且推理能力强
    @Value("${scenario.llm.provider:DEEPSEEK}")
    private String defaultProviderName;

    @Autowired
    public LlmBasedScenarioPerceptionService(List<LlmClient> clients, LlmConfigService llmConfigService) {
        this.llmClients = clients.stream()
                .collect(Collectors.toMap(
                        client -> client.getSupportedProvider().name(),
                        client -> client
                ));
        this.llmConfigService = llmConfigService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public ScenarioAnalysisResult analyzeScenario(DesensitizationRequest request) {
        if (request == null || request.getMainContent() == null || request.getMainContent().trim().isEmpty()) {
            return getDefaultScenario();
        }

        try {
            // 1. 获取LLM客户端和配置
            LlmProvider provider = LlmProvider.valueOf(defaultProviderName);
            LlmClient client = llmClients.get(provider.name());
            LlmConfig config = llmConfigService.getConfigOrDefault(provider);

            if (client == null || config == null) {
                log.warn("未找到配置的LLM提供商: {}, 降级使用默认情景", defaultProviderName);
                return getDefaultScenario();
            }

            // 2. 构建提示词
            String prompt = buildPrompt(request.getMainContent());

            // 3. 调用LLM
            String response = client.sendRequest(prompt, config, new HashMap<>());

            // 4. 解析响应
            return parseLlmResponse(response);

        } catch (Exception e) {
            log.error("LLM情景分析失败: {}", e.getMessage(), e);
            // 发生错误时降级到默认情景
            return getDefaultScenario();
        }
    }

    // TODO: 这里只是构建提示词问AI，所发送的可能包含敏感信息的文本来分析场景来用不同场景下的正则匹配规则，有点本末导致
    private String buildPrompt(String content) {
        return """
                你是一个专业的数据安全专家和情景分析助手。请分析以下用户输入的文本内容，识别其所属的业务情景，并给出相应的敏感信息处理建议。
                
                支持的情景类型包括：
                - GENERAL_CHAT: 普通聊天
                - CUSTOMER_SERVICE: 客户服务（涉及订单、退款等）
                - MEDICAL_CONSULTATION: 医疗咨询（涉及病情、病历等）
                - FINANCIAL_ADVICE: 财务建议（涉及银行卡、股票、理财等）
                - LEGAL_ADVICE: 法律咨询（涉及合同、纠纷等）
                - HR_RECRUITMENT: 招聘（涉及简历、面试等）
                - EDUCATION: 教育（涉及学校、考试等）
                - TECHNICAL_SUPPORT: 技术支持（涉及故障、配置等）
                - CODE_DEVELOPMENT: 代码开发（涉及源代码、API Key等）
                - GOVERNMENT_SERVICE: 政务服务（涉及证件办理等）
                
                请以JSON格式返回分析结果，不要包含Markdown代码块标记，格式如下：
                {
                    "scenarioType": "情景类型枚举值",
                    "confidence": 0.0-1.0之间的置信度,
                    "reasoning": "简短的分析理由",
                    "sensitiveTypes": ["建议重点关注的敏感类型1", "敏感类型2"],
                    "riskLevel": "LOW/MEDIUM/HIGH"
                }
                
                待分析文本：
                %s
                """.formatted(content.length() > 2000 ? content.substring(0, 2000) : content); // 截断过长文本
    }

    private ScenarioAnalysisResult parseLlmResponse(String jsonResponse) {
        try {
            // 清理可能存在的Markdown标记
            String cleanJson = jsonResponse.replaceAll("```json", "").replaceAll("```", "").trim();
            JsonNode root = objectMapper.readTree(cleanJson);

            String scenarioTypeStr = root.path("scenarioType").asText("GENERAL_CHAT");
            double confidence = root.path("confidence").asDouble(0.5);
            String reasoning = root.path("reasoning").asText();
            
            ScenarioAnalysisResult.ScenarioType scenarioType;
            try {
                scenarioType = ScenarioAnalysisResult.ScenarioType.valueOf(scenarioTypeStr);
            } catch (IllegalArgumentException e) {
                scenarioType = ScenarioAnalysisResult.ScenarioType.GENERAL_CHAT;
            }

            ScenarioAnalysisResult result = ScenarioAnalysisResult.builder()
                    .scenarioType(scenarioType)
                    .confidence(confidence)
                    .build();

            // 解析建议的敏感类型
            Set<String> sensitiveTypes = new HashSet<>();
            JsonNode typesNode = root.path("sensitiveTypes");
            if (typesNode.isArray()) {
                for (JsonNode type : typesNode) {
                    try {
                        // 尝试匹配系统定义的SensitiveType
                        SensitiveType st = SensitiveType.valueOf(type.asText());
                        sensitiveTypes.add(st.name());
                    } catch (IllegalArgumentException ignored) {
                        // 忽略系统不支持的类型
                    }
                }
            }
            if (!sensitiveTypes.isEmpty()) {
                result.setSensitiveTypesToInclude(sensitiveTypes);
            }

            // 添加推理信息到参数
            result.addParameter("reasoning", reasoning);
            result.addParameter("riskLevel", root.path("riskLevel").asText("LOW"));

            // 配置动态规则（复用逻辑，但根据LLM的风险评估进行微调）
            configureDynamicRules(result, root.path("riskLevel").asText("LOW"));

            log.info("LLM情景分析完成: Type={}, Confidence={}, Risk={}", scenarioType, confidence, root.path("riskLevel").asText());
            return result;

        } catch (JsonProcessingException e) {
            log.error("解析LLM响应JSON失败: {}", jsonResponse, e);
            return getDefaultScenario();
        }
    }

    private void configureDynamicRules(ScenarioAnalysisResult result, String riskLevel) {
        Map<SensitiveType, Double> thresholds = new HashMap<>();
        Map<SensitiveType, Boolean> strictModes = new HashMap<>();
        
        boolean isHighRisk = "HIGH".equalsIgnoreCase(riskLevel);

        switch (result.getScenarioType()) {
            case FINANCIAL_ADVICE:
                thresholds.put(SensitiveType.BANK_CARD, isHighRisk ? 0.5 : 0.6);
                thresholds.put(SensitiveType.CREDIT_CARD, isHighRisk ? 0.5 : 0.6);
                strictModes.put(SensitiveType.BANK_CARD, true);
                break;
                
            case MEDICAL_CONSULTATION:
                thresholds.put(SensitiveType.ID_CARD, 0.7);
                thresholds.put(SensitiveType.NAME, 0.6);
                break;
                
            case CODE_DEVELOPMENT:
                thresholds.put(SensitiveType.API_KEY, 0.5);
                thresholds.put(SensitiveType.IP_ADDRESS, 0.8);
                break;
                
            case GENERAL_CHAT:
                // 如果LLM认为是普通聊天但风险高（可能是误判或隐含风险），则保持适中阈值
                double generalThreshold = isHighRisk ? 0.7 : 0.9;
                thresholds.put(SensitiveType.BANK_CARD, generalThreshold);
                thresholds.put(SensitiveType.ID_CARD, generalThreshold);
                break;
                
            default:
                break;
        }
        
        result.setTypeConfidenceThresholds(thresholds);
        result.setTypeStrictModes(strictModes);
    }

    @Override
    public void adjustDetectionScope(DesensitizationRequest request, ScenarioAnalysisResult analysisResult) {
        if (request == null || analysisResult == null) {
            return;
        }

        // 复用通用的调整逻辑
        ScenarioAnalysisResult.ScenarioType scenarioType = analysisResult.getScenarioType();
        
        // 只有在高风险或特定场景下才开启全局严格模式
        String riskLevel = (String) analysisResult.getParameters().getOrDefault("riskLevel", "LOW");
        boolean isHighRisk = "HIGH".equalsIgnoreCase(riskLevel);

        if (isHighRisk || 
            scenarioType == ScenarioAnalysisResult.ScenarioType.MEDICAL_CONSULTATION || 
            scenarioType == ScenarioAnalysisResult.ScenarioType.FINANCIAL_ADVICE ||
            scenarioType == ScenarioAnalysisResult.ScenarioType.LEGAL_ADVICE) {
            request.setStrictMode(true);
        } else {
            request.setStrictMode(false);
        }

        // 如果LLM建议了敏感类型，则合并到请求中
        if (analysisResult.getSensitiveTypesToInclude() != null && !analysisResult.getSensitiveTypesToInclude().isEmpty()) {
            request.setIncludeTypes(analysisResult.getSensitiveTypesToInclude());
        }

        if (request.getMetadata() == null) {
            request.setMetadata(new HashMap<>());
        }
        request.getMetadata().put("scenarioAnalysisResult", analysisResult);
        request.getMetadata().put("llmReasoning", analysisResult.getParameters().get("reasoning"));
    }

    @Override
    public ScenarioAnalysisResult getDefaultScenario() {
        return ScenarioAnalysisResult.builder()
                .scenarioType(ScenarioAnalysisResult.ScenarioType.GENERAL_CHAT)
                .confidence(0.5)
                .build();
    }
}
