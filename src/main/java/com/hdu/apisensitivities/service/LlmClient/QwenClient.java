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

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.net.URI;

@Slf4j
@Component
public class QwenClient implements LlmClient {

    private final RestTemplate restTemplate;

    public QwenClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String sendRequest(String prompt, LlmConfig config, Map<String, Object> parameters) {
        log.info("通义千问 API请求准备中，URL: {}, 模型: {}, 温度: {}, 最大令牌数: {}",
                config.getApiUrl(), config.getModel(), config.getTemperature(), config.getMaxTokens());

        try {
            HttpHeaders headers = createHeaders(config);
            Map<String, Object> requestBody = createRequestBody(prompt, config, parameters);
            URI apiUri = Objects.requireNonNull(
                    URI.create(Objects.requireNonNull(config.getApiUrl(), "通义千问 API URL不能为空")));
            Map<String, Object> nonNullRequestBody = Objects.requireNonNull(requestBody);

            RequestEntity<Map<String, Object>> entity = RequestEntity
                    .post(apiUri)
                    .headers(headers)
                    .body(nonNullRequestBody);

            log.info("正在发送通义千问 API请求...");
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    entity,
                    new ParameterizedTypeReference<>() {
                    });

            log.info("通义千问 API响应状态码: {}", response.getStatusCode());
            log.debug("通义千问 API响应体: {}", response.getBody());

            return extractResponse(response.getBody());

        } catch (Exception e) {
            log.error("通义千问 API调用失败，详细信息:", e);
            String errorMessage = e.getMessage() != null ? e.getMessage() : "未提供详细错误信息";
            throw new RuntimeException("通义千问 API调用失败: " + errorMessage);
        }
    }

    @Override
    public LlmProvider getSupportedProvider() {
        return LlmProvider.QWEN;
    }

    @Override
    public boolean validateConfig(LlmConfig config) {
        return config.getApiKey() != null && !config.getApiKey().trim().isEmpty();
    }

    @Override
    public String sendStructuredRequest(Map<String, Object> structuredData, LlmConfig config,
            Map<String, Object> parameters) {
        log.info("通义千问 结构化数据请求准备中，模型: {}, 参数: {}", config.getModel(), parameters);

        try {
            // 将结构化数据转换为JSON字符串
            ObjectMapper mapper = new ObjectMapper();
            String structuredDataJson = mapper.writeValueAsString(structuredData);

            // 构建提示词，说明这是结构化数据
            String prompt = "请分析以下结构化数据:\n" + structuredDataJson;

            // 复用现有的sendRequest方法发送请求
            return sendRequest(prompt, config, parameters);
        } catch (Exception e) {
            log.error("通义千问 结构化数据请求失败", e);
            throw new RuntimeException("通义千问 结构化数据请求失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String sendBinaryRequest(byte[] binaryData, String dataType, LlmConfig config,
            Map<String, Object> parameters) {
        log.info("通义千问 二进制数据请求准备中，数据类型: {}, 模型: {}", dataType, config.getModel());

        try {
            // 将二进制数据转换为Base64编码字符串
            String base64Data = Base64.getEncoder().encodeToString(binaryData);

            // 构建提示词，说明这是二进制数据的Base64编码
            String prompt = String.format("这是一个Base64编码的%s数据，请根据需要进行分析:\n%s", dataType, base64Data);

            // 复用现有的sendRequest方法发送请求
            return sendRequest(prompt, config, parameters);
        } catch (Exception e) {
            log.error("通义千问 二进制数据请求失败", e);
            throw new RuntimeException("通义千问 二进制数据请求失败: " + e.getMessage(), e);
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

    private Map<String, Object> createRequestBody(String prompt, LlmConfig config, Map<String, Object> parameters) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getModel());
        requestBody.put("messages", createMessages(prompt));
        requestBody.put("temperature", config.getTemperature());
        requestBody.put("max_tokens", config.getMaxTokens());
        requestBody.put("stream", false);

        if (parameters != null) {
            requestBody.putAll(parameters);
        }

        return requestBody;
    }

    private Object createMessages(String prompt) {
        return new Object[] {
                Map.of("role", "user", "content", prompt)
        };
    }

    private String extractResponse(Map<String, Object> responseBody) {
        if (responseBody == null) {
            throw new RuntimeException("响应体为空");
        }

        if (responseBody.containsKey("error")) {
            Map<String, Object> error = CollectionTypeUtils.asStringObjectMap(responseBody.get("error"));
            if (error == null) {
                throw new RuntimeException("通义千问 API错误: 无法解析错误详情");
            }
            throw new RuntimeException("通义千问 API错误: " + error.get("message"));
        }

        if (responseBody.containsKey("choices")) {
            java.util.List<Map<String, Object>> choices = CollectionTypeUtils
                    .asStringObjectMapList(responseBody.get("choices"));
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> message = CollectionTypeUtils.asStringObjectMap(choice.get("message"));
                if (message != null && message.get("content") instanceof String content) {
                    return content;
                }
            }
        }

        throw new RuntimeException("无法解析通义千问响应");
    }
}
