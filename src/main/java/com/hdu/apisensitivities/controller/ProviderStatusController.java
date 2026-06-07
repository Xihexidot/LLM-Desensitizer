package com.hdu.apisensitivities.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/gateway/v1")
public class ProviderStatusController {

    @GetMapping("/providers")
    public ResponseEntity<List<Map<String, Object>>> getProviders() {
        List<Map<String, Object>> providers = new ArrayList<>();

        addProvider(providers, "DeepSeek", "https://api.deepseek.com", "DEEPSEEK",
                hasEnv("DEEPSEEK_API_KEY"));
        addProvider(providers, "OpenAI", "https://api.openai.com", "OPENAI",
                hasEnv("OPENAI_API_KEY"));
        addProvider(providers, "通义千问", "https://dashscope.aliyuncs.com", "QWEN",
                hasEnv("QWEN_API_KEY"));
        addProvider(providers, "豆包 (字节)", "https://ark.cn-beijing.volces.com", "DOUBAO",
                hasEnv("DOUBAO_API_KEY"));
        addProvider(providers, "Kimi (Moonshot)", "https://api.moonshot.cn", "KIMI",
                hasEnv("KIMI_API_KEY"));
        addProvider(providers, "混元 (腾讯)", "https://api.hunyuan.cloud.tencent.com", "HUNYUAN",
                hasEnv("HUNYUAN_API_KEY"));
        addProvider(providers, "Ollama (本地)", "http://127.0.0.1:11434", "OLLAMA", true);
        addProvider(providers, "Claude (Anthropic)", "https://api.anthropic.com", "CLAUDE",
                hasEnv("ANTHROPIC_API_KEY"));

        return ResponseEntity.ok(providers);
    }

    private void addProvider(List<Map<String, Object>> list, String name, String endpoint,
            String code, boolean configured) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("code", code);
        p.put("endpoint", endpoint);
        p.put("configured", configured);
        p.put("status", configured ? "available" : "unconfigured");
        list.add(p);
    }

    private boolean hasEnv(String key) {
        String val = System.getenv(key);
        if (val != null && !val.isBlank())
            return true;
        val = System.getProperty(key);
        return val != null && !val.isBlank();
    }
}
