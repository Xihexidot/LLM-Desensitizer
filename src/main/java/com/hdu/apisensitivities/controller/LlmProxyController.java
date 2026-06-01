package com.hdu.apisensitivities.controller;

import com.hdu.apisensitivities.entity.*;
import com.hdu.apisensitivities.service.LlmConfigService;
import com.hdu.apisensitivities.service.LlmProxyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/llm")
public class LlmProxyController {

    private final LlmProxyService llmProxyService;
    private final LlmConfigService configService;

    /**
     * 构造函数，初始化LLM代理控制器所需的依赖项
     *
     * @param llmProxyService LLM代理服务，负责实际的请求处理和转发
     * @param configService   配置服务，管理不同提供商的配置信息
     */
    public LlmProxyController(LlmProxyService llmProxyService, LlmConfigService configService) {
        this.llmProxyService = llmProxyService;
        this.configService = configService;
    }

    /**
     * 新版LLM请求处理接口（支持多供应商）
     * 处理包含完整配置信息的LLM请求，支持多种数据类型
     *
     * @param request 包含请求参数的LlmRequest对象
     * @return 包含响应内容的LlmResponse对象
     */
    @PostMapping("/proxy")
    public ResponseEntity<LlmResponse> processLlmRequest(@RequestBody LlmRequest request) {
        // 确保数据类型设置正确，默认为TEXT
        if (request.getDataType() == null) {
            request.setDataType("TEXT");
        }
        LlmResponse response = llmProxyService.processLlmRequest(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 处理结构化数据的LLM请求
     * 直接接收结构化数据，自动转换为合适的格式发送给LLM服务
     *
     * @param structuredData 结构化的输入数据
     * @param provider       指定的LLM提供商，默认为DEEPSEEK
     * @param model          指定的模型名称（可选）
     * @return 包含响应内容的LlmResponse对象
     */
    @PostMapping("/proxy/structured")
    public ResponseEntity<LlmResponse> processStructuredLlmRequest(@RequestBody Map<String, Object> structuredData,
            @RequestParam(defaultValue = "DEEPSEEK") String provider,
            @RequestParam(required = false) String model) {
        // 构建LLM请求
        LlmRequest request = LlmRequest.builder()
                .provider(LlmProvider.valueOf(provider.toUpperCase()))
                .model(model)
                .dataType("JSON") // 指定为JSON数据类型
                .parameters(Map.of("structured_data", structuredData))
                .build();

        LlmResponse response = llmProxyService.processLlmRequest(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 兼容旧版LLM请求
     * 处理使用旧版API格式的请求
     *
     * @param request 包含请求参数的ApiRequest对象
     * @return 包含响应内容的ApiResponse对象
     */
    @PostMapping("/proxy/legacy")
    public ResponseEntity<ApiResponse> processLlmRequestLegacy(@RequestBody ApiRequest request) {
        ApiResponse response = llmProxyService.processLlmRequest(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 异步处理LLM请求
     * 以非阻塞方式处理LLM请求，适用于长时间运行的操作
     *
     * @param request 包含请求参数的LlmRequest对象
     * @return CompletableFuture包装的响应实体
     */
    @PostMapping("/proxy/async")
    public CompletableFuture<ResponseEntity<LlmResponse>> processLlmRequestAsync(@RequestBody LlmRequest request) {
        // 确保数据类型设置正确，默认为TEXT
        if (request.getDataType() == null) {
            request.setDataType("TEXT");
        }
        return llmProxyService.processLlmRequestAsync(request)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * 异步处理旧版LLM请求
     * 以非阻塞方式处理使用旧版API格式的请求
     *
     * @param request 包含请求参数的ApiRequest对象
     * @return CompletableFuture包装的响应实体
     */
    @PostMapping("/proxy/async/legacy")
    public CompletableFuture<ResponseEntity<ApiResponse>> processLlmRequestAsyncLegacy(
            @RequestBody ApiRequest request) {
        return llmProxyService.processLlmRequestAsync(request)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * 批量处理LLM请求
     * 同时处理多个LLM请求，提高处理效率
     *
     * @param requests 包含多个LlmRequest对象的列表
     * @return 包含所有响应的映射，键为请求标识符，值为对应的响应
     */
    @PostMapping("/proxy/batch")
    public ResponseEntity<Map<String, LlmResponse>> batchProcessLlmRequests(@RequestBody List<LlmRequest> requests) {
        // 确保每个请求都有数据类型设置
        requests.forEach(request -> {
            if (request.getDataType() == null) {
                request.setDataType("TEXT");
            }
        });
        Map<String, LlmResponse> responses = llmProxyService.batchProcessLlmRequests(requests);
        return ResponseEntity.ok(responses);
    }

    /**
     * 异步处理结构化数据的LLM请求
     * 以非阻塞方式处理结构化数据，适用于长时间运行的操作
     *
     * @param structuredData 结构化的输入数据
     * @param provider       指定的LLM提供商，默认为DEEPSEEK
     * @param model          指定的模型名称（可选）
     * @return CompletableFuture包装的响应实体
     */
    @PostMapping("/proxy/async/structured")
    public CompletableFuture<ResponseEntity<LlmResponse>> processStructuredLlmRequestAsync(
            @RequestBody Map<String, Object> structuredData,
            @RequestParam(defaultValue = "DEEPSEEK") String provider,
            @RequestParam(required = false) String model) {
        // 构建LLM请求
        LlmRequest request = LlmRequest.builder()
                .provider(LlmProvider.valueOf(provider.toUpperCase()))
                .model(model)
                .dataType("JSON")
                .parameters(Map.of("structured_data", structuredData))
                .build();

        return llmProxyService.processLlmRequestAsync(request)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * 批量处理旧版LLM请求
     * 同时处理多个使用旧版API格式的请求
     *
     * @param requests 包含多个ApiRequest对象的列表
     * @return 包含所有响应的映射，键为请求标识符，值为对应的响应
     */
    @PostMapping("/proxy/batch/legacy")
    public ResponseEntity<Map<String, ApiResponse>> batchProcessLlmRequestsLegacy(
            @RequestBody List<ApiRequest> requests) {
        Map<String, ApiResponse> responses = llmProxyService.batchProcessLlmRequestsLegacy(requests);
        return ResponseEntity.ok(responses);
    }

    /**
     * 获取所有提供商的状态
     * 返回系统中配置的所有LLM提供商及其启用状态
     *
     * @return 包含提供商及其状态的映射
     */
    @GetMapping("/providers")
    public ResponseEntity<Map<LlmProvider, Boolean>> getProviders() {
        Map<LlmProvider, Boolean> providerStatus = llmProxyService.testAllProviders();
        return ResponseEntity.ok(providerStatus);
    }

    /**
     * 获取所有提供商的配置信息
     * 返回系统中所有LLM提供商的详细配置信息
     *
     * @return 包含提供商及其配置的映射
     */
    @GetMapping("/configs")
    public ResponseEntity<Map<LlmProvider, Object>> getConfigs() {
        Map<LlmProvider, Object> configs = configService.getAllConfigs().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            var config = entry.getValue();
                            return Map.of(
                                    "enabled", configService.isProviderEnabled(entry.getKey()),
                                    "model", config.getModel(),
                                    "url", config.getApiUrl());
                        }));
        return ResponseEntity.ok(configs);
    }

    /**
     * 测试特定提供商的连接性
     * 验证指定LLM提供商的配置是否有效且可以正常访问
     *
     * @param provider 要测试的LLM提供商
     * @return 包含测试结果的字符串信息
     */
    @PostMapping("/providers/{provider}/test")
    public ResponseEntity<String> testProvider(@PathVariable LlmProvider provider) {
        try {
            boolean isValid = configService.isProviderEnabled(provider);
            return ResponseEntity.ok("提供商 " + provider + " 测试结果: " + (isValid ? "正常" : "配置异常"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("提供商测试失败: " + e.getMessage());
        }
    }
}
