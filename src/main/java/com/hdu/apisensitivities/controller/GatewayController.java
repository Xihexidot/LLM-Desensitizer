package com.hdu.apisensitivities.controller;

import com.hdu.apisensitivities.entity.*;
import com.hdu.apisensitivities.service.LlmProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 通用网关入口
 * 让任何AI应用（Cursor、Kimi等）都可以通过这个入口，强制经过脱敏检查后再访问大模型
 */
@Slf4j
@RestController
public class GatewayController {

    @Autowired
    private LlmProxyService llmProxyService;

    /**
     * 通用聊天入口（兼容OpenAI API格式）
     * 前端或任何AI工具只需要把请求发到这里，网关自动完成脱敏并转发
     */
    @PostMapping("/api/v0/chat/completion")
    public ResponseEntity<Map<String, Object>> gatewayChat(@RequestBody Map<String, Object> deepseekRequest) {
        // 1. 提取用户问题（DeepSeek的请求里是 "prompt" 字段）
        String userQuestion = (String) deepseekRequest.get("prompt");

        // 【审计日志】记录原始请求
        log.info("【网关入口】原始请求: {}", userQuestion);

        if (userQuestion == null) {
            userQuestion = deepseekRequest.toString();
        }

        // 2. 复用你现有的脱敏逻辑
        LlmRequest llmRequest = LlmRequest.builder()
                .provider(LlmProvider.DEEPSEEK)
                .prompt(userQuestion)
                .dataType("TEXT")
                .sessionId("gateway-" + System.currentTimeMillis())
                .build();

        LlmResponse llmResponse = llmProxyService.processLlmRequest(llmRequest);

        // 3. 构造 DeepSeek 前端能识别的响应格式
        Map<String, Object> response = new HashMap<>();
        if (llmResponse.isSuccess()) {
            response.put("code", 0);
            response.put("msg", "success");
            // DeepSeek 期望的回复体
            Map<String, Object> data = new HashMap<>();
            data.put("content", llmResponse.getDesensitizedResponse());
            data.put("role", "assistant");
            response.put("data", data);

            // 【审计日志】记录脱敏后转发成功
            log.info("【网关转发成功】大模型输出内容: {} | 检测到的敏感实体: {}",
                    llmResponse.getDesensitizedResponse(),
                    llmResponse.getInputSensitiveEntities());

        } else {
            response.put("code", -1);
            response.put("msg", "根据安全策略，您的请求已被拦截");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 健康检查（方便调试）
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Gateway is running");

    }
}