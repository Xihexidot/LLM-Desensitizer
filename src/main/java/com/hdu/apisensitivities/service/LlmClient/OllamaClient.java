package com.hdu.apisensitivities.service.LlmClient;

import com.hdu.apisensitivities.config.LlmConfig;
import com.hdu.apisensitivities.entity.LlmProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class OllamaClient extends DeepSeekClient {

    public OllamaClient(RestTemplate restTemplate) {
        super(restTemplate);
    }

    @Override
    public LlmProvider getSupportedProvider() {
        return LlmProvider.OLLAMA;
    }

    @Override
    public boolean validateConfig(LlmConfig config) {
        // Ollama本地调用，只要有URL就行
        return config.getApiUrl() != null && !config.getApiUrl().trim().isEmpty();
    }

    // 注意：这里不要写 @Override，因为父类 DeepSeekClient 中的 createHeaders 是 private 的
    // 我们在这里定义一个属于 OllamaClient 的私有方法，或者修改父类方法为 protected
    public HttpHeaders createOllamaHeaders(LlmConfig config) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Ollama 默认不需要 Bearer Token，如果配置了也加上
        if (config.getApiKey() != null && !config.getApiKey().trim().isEmpty()) {
            headers.set("Authorization", "Bearer " + config.getApiKey());
        }
        return headers;
    }
}
