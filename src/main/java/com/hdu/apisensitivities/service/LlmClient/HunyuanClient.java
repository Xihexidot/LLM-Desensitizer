package com.hdu.apisensitivities.service.LlmClient;

import com.hdu.apisensitivities.config.LlmConfig;
import com.hdu.apisensitivities.entity.LlmProvider;
import com.hdu.apisensitivities.utils.CollectionTypeUtils;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 腾讯混元大模型客户端实现
 */
@Slf4j
@Component
public class HunyuanClient implements LlmClient {

    private final RestTemplate restTemplate;

    public HunyuanClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String sendRequest(String prompt, LlmConfig config, Map<String, Object> parameters) {
        log.info("发送请求到腾讯混元大模型，模型: {}, 参数: {}", config.getModel(), parameters);

        try {
            // 构建请求体
            Map<String, Object> requestBody = buildRequestBody(prompt, config, parameters);

            // 创建请求头
            HttpHeaders headers = createHeaders(config);

            // 创建请求实体
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

            // 发送请求
            ResponseEntity<Map<String, Object>> responseEntity = restTemplate.exchange(
                    Objects.requireNonNull(config.getApiUrl(), "腾讯混元 API URL不能为空"),
                    Objects.requireNonNull(HttpMethod.POST),
                    requestEntity,
                    new ParameterizedTypeReference<>() {
                    });

            // 解析响应
            return parseResponse(responseEntity.getBody());
        } catch (Exception e) {
            log.error("调用腾讯混元大模型API失败", e);
            throw new RuntimeException("调用腾讯混元大模型API失败: " + e.getMessage(), e);
        }
    }

    @Override
    public LlmProvider getSupportedProvider() {
        return LlmProvider.HUNYUAN;
    }

    @Override
    public boolean validateConfig(LlmConfig config) {
        return config.getApiKey() != null && !config.getApiKey().trim().isEmpty();
    }

    @Override
    public String sendStructuredRequest(Map<String, Object> structuredData, LlmConfig config,
            Map<String, Object> parameters) {
        log.info("腾讯混元 结构化数据请求准备中，模型: {}, 参数: {}", config.getModel(), parameters);

        try {
            // 将结构化数据转换为JSON字符串
            ObjectMapper mapper = new ObjectMapper();
            String structuredDataJson = mapper.writeValueAsString(structuredData);

            // 构建提示词，说明这是结构化数据
            String prompt = "请分析以下结构化数据:\n" + structuredDataJson;

            // 复用现有的sendRequest方法发送请求
            return sendRequest(prompt, config, parameters);
        } catch (Exception e) {
            log.error("腾讯混元 结构化数据请求失败", e);
            throw new RuntimeException("腾讯混元 结构化数据请求失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String sendBinaryRequest(byte[] binaryData, String dataType, LlmConfig config,
            Map<String, Object> parameters) {
        log.info("腾讯混元 二进制数据请求准备中，数据类型: {}, 模型: {}", dataType, config.getModel());

        try {
            // 将二进制数据转换为Base64编码字符串
            String base64Data = Base64.getEncoder().encodeToString(binaryData);

            // 构建提示词，说明这是二进制数据的Base64编码
            String prompt = String.format("这是一个Base64编码的%s数据，请根据需要进行分析:\n%s", dataType, base64Data);

            // 复用现有的sendRequest方法发送请求
            return sendRequest(prompt, config, parameters);
        } catch (Exception e) {
            log.error("腾讯混元 二进制数据请求失败", e);
            throw new RuntimeException("腾讯混元 二进制数据请求失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supportsDataType(String dataType) {
        // 支持的结构化数据类型
        Set<String> supportedTypes = Set.of("TEXT", "JSON", "XML");
        return supportedTypes.contains(dataType.toUpperCase());
    }

    public HttpHeaders createHeaders(LlmConfig config) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }

    private Map<String, Object> buildRequestBody(String prompt, LlmConfig config, Map<String, Object> parameters) {
        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> effectiveParameters = parameters == null ? Map.of() : parameters;

        // 设置模型
        requestBody.put("model", config.getModel());

        // 设置消息
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);
        requestBody.put("messages", messages);

        // 设置温度
        requestBody.put("temperature", effectiveParameters.getOrDefault("temperature", config.getTemperature()));

        // 设置最大令牌数
        requestBody.put("max_tokens", effectiveParameters.getOrDefault("maxTokens", config.getMaxTokens()));

        // 设置停止词
        if (effectiveParameters.containsKey("stop")) {
            requestBody.put("stop", effectiveParameters.get("stop"));
        }

        // 设置频率惩罚
        if (effectiveParameters.containsKey("frequencyPenalty")) {
            requestBody.put("frequency_penalty", effectiveParameters.get("frequencyPenalty"));
        }

        // 设置存在惩罚
        if (effectiveParameters.containsKey("presencePenalty")) {
            requestBody.put("presence_penalty", effectiveParameters.get("presencePenalty"));
        }

        // 设置top_p
        if (effectiveParameters.containsKey("topP")) {
            requestBody.put("top_p", effectiveParameters.get("topP"));
        }

        return requestBody;
    }

    private String parseResponse(Map<String, Object> responseBody) {
        if (responseBody == null || !responseBody.containsKey("choices")) {
            throw new RuntimeException("无效的腾讯混元大模型响应: 缺少choices字段");
        }

        List<Map<String, Object>> choices = CollectionTypeUtils.asStringObjectMapList(responseBody.get("choices"));
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("无效的腾讯混元大模型响应: choices列表为空");
        }

        Map<String, Object> firstChoice = choices.get(0);
        if (!firstChoice.containsKey("message")) {
            throw new RuntimeException("无效的腾讯混元大模型响应: 缺少message字段");
        }

        Map<String, Object> message = CollectionTypeUtils.asStringObjectMap(firstChoice.get("message"));
        if (message == null || !message.containsKey("content")) {
            throw new RuntimeException("无效的腾讯混元大模型响应: 缺少content字段");
        }

        return (String) message.get("content");
    }
}
