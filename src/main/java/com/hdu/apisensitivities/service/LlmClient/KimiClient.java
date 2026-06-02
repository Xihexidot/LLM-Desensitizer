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

@Slf4j
@Component
public class KimiClient implements LlmClient {

    private final RestTemplate restTemplate;

    public KimiClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String sendRequest(String prompt, LlmConfig config, Map<String, Object> parameters) {
        log.info("Kimi API请求准备中，URL: {}, 模型: {}, 温度: {}, 最大令牌数: {}", 
                config.getApiUrl(), config.getModel(), config.getTemperature(), config.getMaxTokens());
        
        try {
            HttpHeaders headers = createHeaders(config);
            Map<String, Object> requestBody = createRequestBody(prompt, config, parameters);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("正在发送Kimi API请求...");
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    Objects.requireNonNull(config.getApiUrl(), "Kimi API URL不能为空"),
                    Objects.requireNonNull(HttpMethod.POST),
                    entity,
                    new ParameterizedTypeReference<>() {});
            
            log.info("Kimi API响应状态码: {}", response.getStatusCode());
            log.debug("Kimi API响应体: {}", response.getBody());

            return extractResponse(response.getBody());

        } catch (Exception e) {
            log.error("Kimi API调用失败，详细信息:", e);
            String errorMessage = e.getMessage() != null ? e.getMessage() : "未提供详细错误信息";
            throw new RuntimeException("Kimi API调用失败: " + errorMessage);
        }
    }

    @Override
    public LlmProvider getSupportedProvider() {
        return LlmProvider.KIMI;
    }

    @Override
    public boolean validateConfig(LlmConfig config) {
        return config.getApiKey() != null && !config.getApiKey().trim().isEmpty();
    }
    
    @Override
    public String sendStructuredRequest(Map<String, Object> structuredData, LlmConfig config, Map<String, Object> parameters) {
        log.info("Kimi 结构化数据请求准备中，模型: {}, 参数: {}", config.getModel(), parameters);
        
        try {
            // 将结构化数据转换为JSON字符串
            ObjectMapper mapper = new ObjectMapper();
            String structuredDataJson = mapper.writeValueAsString(structuredData);
            
            // 构建提示词，说明这是结构化数据
            String prompt = "请分析以下结构化数据:\n" + structuredDataJson;
            
            // 复用现有的sendRequest方法发送请求
            return sendRequest(prompt, config, parameters);
        } catch (Exception e) {
            log.error("Kimi 结构化数据请求失败", e);
            throw new RuntimeException("Kimi 结构化数据请求失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public String sendBinaryRequest(byte[] binaryData, String dataType, LlmConfig config, Map<String, Object> parameters) {
        log.info("Kimi 二进制数据请求准备中，数据类型: {}, 模型: {}", dataType, config.getModel());
        
        try {
            // 将二进制数据转换为Base64编码字符串
            String base64Data = Base64.getEncoder().encodeToString(binaryData);
            
            // 构建提示词，说明这是二进制数据的Base64编码
            String prompt = String.format("这是一个Base64编码的%s数据，请根据需要进行分析:\n%s", dataType, base64Data);
            
            // 复用现有的sendRequest方法发送请求
            return sendRequest(prompt, config, parameters);
        } catch (Exception e) {
            log.error("Kimi 二进制数据请求失败", e);
            throw new RuntimeException("Kimi 二进制数据请求失败: " + e.getMessage(), e);
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
        Map<String, Object> effectiveParameters = parameters == null ? Map.of() : parameters;
        requestBody.put("model", config.getModel());
        
        // 构建messages数组
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);
        requestBody.put("messages", messages);
        
        // 添加参数
        requestBody.put("temperature", effectiveParameters.getOrDefault("temperature", config.getTemperature()));
        requestBody.put("max_tokens", effectiveParameters.getOrDefault("maxTokens", config.getMaxTokens()));
        
        // 添加其他参数
        effectiveParameters.forEach((key, value) -> {
            if (!"temperature".equals(key) && !"maxTokens".equals(key)) {
                requestBody.put(key, value);
            }
        });
        
        return requestBody;
    }

    private String extractResponse(Map<String, Object> responseBody) {
        if (responseBody == null) {
            throw new RuntimeException("Kimi API返回空响应");
        }
        
        List<Map<String, Object>> choices = CollectionTypeUtils.asStringObjectMapList(responseBody.get("choices"));
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("Kimi API响应中没有choices字段");
        }
        
        Map<String, Object> choice = choices.get(0);
        Map<String, Object> message = CollectionTypeUtils.asStringObjectMap(choice.get("message"));
        if (message == null) {
            throw new RuntimeException("Kimi API响应中没有message字段");
        }
        
        if (message.get("content") instanceof String content) {
            return content;
        }
        throw new RuntimeException("Kimi API响应中没有content字段");
    }
}
