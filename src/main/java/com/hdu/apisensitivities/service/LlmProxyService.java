package com.hdu.apisensitivities.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdu.apisensitivities.config.LlmConfig;
import com.hdu.apisensitivities.entity.*;
import com.hdu.apisensitivities.service.LlmClient.LlmClient;
import com.hdu.apisensitivities.utils.CollectionTypeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import com.hdu.apisensitivities.service.SensitiveDetection.NlpScanner;
import com.hdu.apisensitivities.service.Desensitization.SemanticPlaceholderStrategy;

/**
 * LLM 代理服务，负责处理大语言模型请求的全流程。
 * <p>
 * 主要功能包括：
 * <ul>
 * <li>接收 {@link LlmRequest} 或 {@link ApiRequest} 请求</li>
 * <li>根据数据类型（文本/JSON/二进制等）进行敏感信息脱敏</li>
 * <li>调用对应的 {@link LlmClient} 实现与真实 LLM API 交互</li>
 * <li>对返回内容再次脱敏并封装为 {@link LlmResponse} 或 {@link ApiResponse}</li>
 * <li>支持同步、异步、批量处理及提供商健康测试</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class LlmProxyService {
    private final NlpScanner nlpScanner;
    private final SemanticPlaceholderStrategy semanticPlaceholderStrategy;
    private final DesensitizationManager desensitizationManager;
    private final LlmConfigService configService;
    private final Map<LlmProvider, LlmClient> llmClients;

    /**
     * 构造 LLM 代理服务实例。
     *
     * @param desensitizationManager 敏感信息脱敏管理器，用于输入/输出的内容脱敏
     * @param configService          LLM 提供商配置服务，获取各提供商的 API 密钥、端点等配置
     * @param clients                所有已注册的 LLM 客户端实现，将按支持的提供商自动映射
     */
    public LlmProxyService(NlpScanner nlpScanner,
            SemanticPlaceholderStrategy semanticPlaceholderStrategy,
            DesensitizationManager desensitizationManager,
            LlmConfigService configService,
            List<LlmClient> clients) {
        this.nlpScanner = nlpScanner;
        this.semanticPlaceholderStrategy = semanticPlaceholderStrategy;
        this.desensitizationManager = desensitizationManager;
        this.configService = configService;
        this.llmClients = clients.stream()
                .collect(Collectors.toMap(LlmClient::getSupportedProvider, client -> client));
    }

    // 将 NlpScanner 识别出的字符串转换为系统实体对象
    private List<SensitiveEntity> convertToSensitiveEntities(List<String> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        return entities.stream().map(name -> {
            SensitiveEntity entity = new SensitiveEntity();
            entity.setContent(name);
            entity.setOriginalText(name);
            entity.setConfidence(0.95);
            return entity;
        }).collect(Collectors.toList());
    }

    /**
     * 🌟 增强版：实现 Agent 的自我反思 (Self-Reflection)
     * 在这个方法里可以调用 nlpScanner.checkSafety 进行自检
     */
    private boolean agentSelfReflection(String maskedText) {
        log.info("Agent 正在对脱敏结果进行自我反思审计...");
        boolean isStillDangerous = nlpScanner.checkSafety(maskedText);
        if (isStillDangerous) {
            log.warn("反思结论：当前脱敏结果存在残留风险！");
        } else {
            log.info("反思结论：当前文本安全，准予发送至云端。");
        }
        return isStillDangerous;
    }

    // 处理LLM请求（新版）
    public LlmResponse processLlmRequest(LlmRequest request) {
        Instant start = Instant.now();

        try {
            LlmProvider provider = request.getProvider();
            LlmConfig config = getProviderConfig(provider);
            logRequestStart(request, provider);
            validateProviderEnabled(provider);

            DesensitizationResult result = processWithDataSensitiveProtection(request, config);
            long processingTime = Duration.between(start, Instant.now()).toMillis();
            return buildSuccessResponse(request, provider, config, result, processingTime);

        } catch (Exception e) {
            log.error("处理LLM请求失败", e);
            long processingTime = Duration.between(start, Instant.now()).toMillis();
            return buildFailedResponse(request, processingTime, e);
        }
    }

    private LlmConfig getProviderConfig(LlmProvider provider) {
        return configService.getConfigOrDefault(provider);
    }

    private void logRequestStart(LlmRequest request, LlmProvider provider) {
        log.info("开始处理LLM请求，提供商: {}, 会话ID: {}, 数据类型: {}",
                provider, request.getSessionId(), request.getDataType());
    }

    private void validateProviderEnabled(LlmProvider provider) {
        if (!configService.isProviderEnabled(provider)) {
            throw new RuntimeException("LLM提供商未启用或配置不完整: " + provider);
        }
    }

    private LlmResponse buildSuccessResponse(LlmRequest request, LlmProvider provider, LlmConfig config,
            DesensitizationResult result, long processingTime) {
        LlmResponse.LlmResponseBuilder responseBuilder = LlmResponse.builder()
                .originalResponse(result.getOriginalResponse())
                .desensitizedResponse(result.getDesensitizedResponse())
                .inputSensitiveEntities(result.getInputEntities())
                .outputSensitiveEntities(result.getOutputEntities())
                .provider(provider)
                .model(config.getModel())
                .processingTimeMs(processingTime)
                .success(true)
                .dataType(request.getDataType());

        attachStructuredResponseIfNeeded(responseBuilder, request, result);
        return responseBuilder.build();
    }

    private void attachStructuredResponseIfNeeded(LlmResponse.LlmResponseBuilder responseBuilder, LlmRequest request,
            DesensitizationResult result) {
        if (!isStructuredResponseType(request.getDataType())) {
            return;
        }

        try {
            Map<String, Object> structuredData = parseJson(result.getDesensitizedResponse());
            if (structuredData != null && !structuredData.isEmpty()) {
                responseBuilder.structuredResponse(structuredData);
            }
        } catch (Exception e) {
            log.warn("无法将响应解析为结构化数据: {}", e.getMessage());
        }
    }

    private boolean isStructuredResponseType(String dataType) {
        return "JSON".equals(dataType) || "XML".equals(dataType);
    }

    private LlmResponse buildFailedResponse(LlmRequest request, long processingTime, Exception e) {
        return LlmResponse.builder()
                .originalResponse(null)
                .desensitizedResponse(null)
                .inputSensitiveEntities(List.of())
                .outputSensitiveEntities(List.of())
                .provider(request.getProvider())
                .processingTimeMs(processingTime)
                .success(false)
                .errorMessage(e.getMessage())
                .build();
    }

    // 处理LLM请求（兼容旧版ApiRequest）
    public ApiResponse processLlmRequest(ApiRequest request) {
        LlmRequest llmRequest = request.toLlmRequest();
        LlmResponse llmResponse = processLlmRequest(llmRequest);
        return llmResponse.toApiResponse();
    }

    /**
     * 异步处理 LLM 请求（新版）。
     *
     * @param request LLM 请求对象
     * @return 包含 {@link LlmResponse} 的 CompletableFuture
     */
    @Async
    public CompletableFuture<LlmResponse> processLlmRequestAsync(LlmRequest request) {
        return CompletableFuture.completedFuture(processLlmRequest(request));
    }

    @Async
    public CompletableFuture<ApiResponse> processLlmRequestAsync(ApiRequest request) {
        return CompletableFuture.completedFuture(processLlmRequest(request));
    }

    // 批量处理LLM请求
    public Map<String, LlmResponse> batchProcessLlmRequests(List<LlmRequest> requests) {
        return requests.parallelStream()
                .collect(Collectors.toMap(
                        LlmRequest::getSessionId,
                        this::processLlmRequest));
    }

    public Map<String, ApiResponse> batchProcessLlmRequestsLegacy(List<ApiRequest> requests) {
        return requests.parallelStream()
                .collect(Collectors.toMap(
                        ApiRequest::getSessionId,
                        this::processLlmRequest));
    }

    /**
     * 测试所有已注册 LLM 提供商的配置是否有效。
     * <p>
     * 依次调用每个客户端的 {@link LlmClient#validateConfig(LlmConfig)} 方法。
     * </p>
     *
     * @return 每个提供商对应的测试结果（true=配置有效，false=无效或异常）
     */
    public Map<LlmProvider, Boolean> testAllProviders() {
        return llmClients.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            try {
                                LlmConfig config = configService.getConfigOrDefault(entry.getKey());
                                return configService.isProviderEnabled(entry.getKey()) &&
                                        entry.getValue().validateConfig(config);
                            } catch (Exception e) {
                                log.warn("提供商测试失败: {}", entry.getKey(), e);
                                return false;
                            }
                        }));
    }

    /**
     * 根据请求的数据类型执行输入脱敏、API 调用和输出脱敏的核心逻辑。
     *
     * @param request 原始 LLM 请求
     * @param config  对应提供商的配置
     * @return 封装了原始响应、脱敏响应及输入/输出敏感实体的结果对象
     */
    private DesensitizationResult processWithDataSensitiveProtection(LlmRequest request, LlmConfig config) {
        DesensitizationRequest inputRequest = buildDesensitizationRequestForLlm(request);
        DesensitizationResponse baseDesensitized = desensitizationManager.process(inputRequest);
        List<String> aiEntities = extractSemanticEntities(baseDesensitized);
        String maskedPrompt = buildMaskedPrompt(baseDesensitized, aiEntities);
        auditMaskedPromptIfNeeded(aiEntities, maskedPrompt);

        String llmRawResponse = callLlmApiWithDataType(
                inputRequest,
                baseDesensitized,
                maskedPrompt,
                config,
                request.getParameters(),
                request.getProvider());

        String finalResponse = restoreResponse(llmRawResponse);
        List<SensitiveEntity> allEntities = mergeInputEntities(baseDesensitized, aiEntities);

        return new DesensitizationResult(
                llmRawResponse,
                finalResponse,
                allEntities,
                List.of());
    }

    private List<String> extractSemanticEntities(DesensitizationResponse baseDesensitized) {
        return nlpScanner.extractEntities(baseDesensitized.getDesensitizedContent());
    }

    private String buildMaskedPrompt(DesensitizationResponse baseDesensitized, List<String> aiEntities) {
        return semanticPlaceholderStrategy.desensitize(
                baseDesensitized.getDesensitizedContent(),
                aiEntities);
    }

    private void auditMaskedPromptIfNeeded(List<String> aiEntities, String maskedPrompt) {
        if (aiEntities.isEmpty()) {
            return;
        }

        log.info("Agent检测到语义敏感词，执行隐私保护策略...");
        boolean dangerous = agentSelfReflection(maskedPrompt);
        if (dangerous) {
            log.error("警告：Agent 自检发现脱敏不彻底，请检查本地模型识别能力。");
        }
    }

    private String restoreResponse(String llmRawResponse) {
        return semanticPlaceholderStrategy.restore(llmRawResponse);
    }

    private List<SensitiveEntity> mergeInputEntities(DesensitizationResponse baseDesensitized, List<String> aiEntities) {
        List<SensitiveEntity> allEntities = new ArrayList<>(baseDesensitized.getDetectedEntities());
        allEntities.addAll(convertToSensitiveEntities(aiEntities));
        return allEntities;
    }

    // 创建基本的脱敏请求对象
    private DesensitizationRequest createBaseDesensitizationRequest(LlmRequest request, String dataType) {
        DesensitizationRequest desensitizationRequest = new DesensitizationRequest();
        desensitizationRequest.setLanguage("mixed");
        desensitizationRequest.setStrictMode(true);
        desensitizationRequest.setBlacklist(request.getBlacklist());
        desensitizationRequest.setWhitelist(request.getWhitelist());
        desensitizationRequest.setPreserveStructure(true);
        desensitizationRequest.setDataType(dataType);
        return desensitizationRequest;
    }

    // 为LLM请求构建脱敏请求
    private DesensitizationRequest buildDesensitizationRequestForLlm(LlmRequest request) {
        String dataType = request.getDataType() != null ? request.getDataType() : "TEXT";

        DesensitizationRequest desensitizationRequest = createBaseDesensitizationRequest(request, dataType);

        switch (dataType) {
            case "JSON", "XML":
                populateStructuredRequestContent(request, desensitizationRequest);
                break;
            case "IMAGE", "AUDIO", "PDF", "DOC":
                populateBinaryRequestContent(request, desensitizationRequest);
                break;
            default:
                populateTextRequestContent(request, desensitizationRequest);
                break;
        }

        applyIncludeTypesIfPresent(desensitizationRequest, request);
        return desensitizationRequest;
    }

    private void applyIncludeTypesIfPresent(DesensitizationRequest req, LlmRequest request) {
        if (request.getIncludeTypes() != null && !request.getIncludeTypes().isEmpty()) {
            req.setIncludeTypes(request.getIncludeTypes());
            req.setAutoScenarioDetection(false);
            log.debug("场景感知检测范围: {}", request.getIncludeTypes());
        }
    }

    private void populateStructuredRequestContent(LlmRequest request, DesensitizationRequest desensitizationRequest) {
        if (hasParameter(request, "structuredData")) {
            Object structuredDataObj = request.getParameters().get("structuredData");
            Map<String, Object> structuredData = CollectionTypeUtils.asStringObjectMap(structuredDataObj);
            if (structuredData != null) {
                desensitizationRequest.setStructuredData(structuredData);
                log.debug("使用参数中的结构化数据，字段数量: {}", structuredData.size());
            } else {
                log.warn("structuredData参数不是Map类型，实际类型: {}",
                        structuredDataObj != null ? structuredDataObj.getClass().getName() : "null");
            }
            return;
        }

        if (request.getPrompt() == null) {
            return;
        }

        try {
            Map<String, Object> parsedData = parseJson(request.getPrompt());
            if (parsedData != null && !parsedData.isEmpty()) {
                desensitizationRequest.setStructuredData(parsedData);
                log.debug("成功将prompt解析为结构化数据，字段数量: {}", parsedData.size());
            } else {
                desensitizationRequest.setContent(request.getPrompt());
                log.debug("prompt解析为结构化数据但为空，使用原始文本");
            }
        } catch (Exception e) {
            desensitizationRequest.setContent(request.getPrompt());
            log.debug("无法将prompt解析为结构化数据: {}, 使用原始文本", e.getMessage());
        }
    }

    private void populateBinaryRequestContent(LlmRequest request, DesensitizationRequest desensitizationRequest) {
        if (hasParameter(request, "binaryData")) {
            Object binaryDataObj = request.getParameters().get("binaryData");
            if (binaryDataObj instanceof byte[]) {
                byte[] binaryData = (byte[]) binaryDataObj;
                desensitizationRequest.setBinaryData(binaryData);
                log.debug("使用二进制数据，大小: {} 字节", binaryData != null ? binaryData.length : 0);
            } else {
                log.warn("binaryData参数不是byte[]类型，实际类型: {}",
                        binaryDataObj != null ? binaryDataObj.getClass().getName() : "null");
            }
        }

        if (request.getPrompt() != null) {
            desensitizationRequest.setContent(request.getPrompt());
            log.debug("使用文本描述: {}", buildPromptPreview(request.getPrompt()));
        }
    }

    private void populateTextRequestContent(LlmRequest request, DesensitizationRequest desensitizationRequest) {
        desensitizationRequest.setContent(request.getPrompt());
        log.debug("使用文本数据: {}", buildPromptPreview(request.getPrompt()));
    }

    private boolean hasParameter(LlmRequest request, String key) {
        return request.getParameters() != null && request.getParameters().containsKey(key);
    }

    private String buildPromptPreview(String prompt) {
        if (prompt == null) {
            return null;
        }
        return prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt;
    }

    // 根据数据类型调用LLM API
    private String callLlmApiWithDataType(DesensitizationRequest inputRequest,
            DesensitizationResponse inputDesensitized,
            String maskedContent,
            LlmConfig config,
            Map<String, Object> parameters,
            LlmProvider provider) {
        log.info("调用真实LLM API，提供商: {}, 数据类型: {}, 敏感实体数: {}",
                provider, inputRequest.getDataType(), inputDesensitized.getDetectedEntities().size());

        LlmClient client = llmClients.get(provider);
        String dataType = inputRequest.getDataType() != null ? inputRequest.getDataType().toUpperCase() : "TEXT";
        boolean isDataTypeSupported = client.supportsDataType(dataType);
        Map<String, Object> processedParams = prepareParamsForDataType(inputRequest, inputDesensitized, parameters);

        try {
            if (inputRequest.isBinaryData()) {
                if (isDataTypeSupported) {
                    return client.sendBinaryRequest(inputRequest.getBinaryData(), dataType, config, processedParams);
                } else {
                    // 兼容模式处理二进制
                    String prompt = generatePromptForBinaryData(inputRequest, inputDesensitized);
                    return client.sendRequest(prompt, config, processedParams);
                }
            } else if (inputRequest.isStructuredData()) {
                if (isDataTypeSupported) {
                    return client.sendStructuredRequest(inputRequest.getStructuredData(), config, processedParams);
                } else {
                    // 兼容模式处理结构化数据
                    String prompt = generatePromptForStructuredData(inputRequest, inputDesensitized);
                    return client.sendRequest(prompt, config, processedParams);
                }
            } else {
                // 如果是文本，优先使用 Agent 识别并打码后的 maskedContent
                log.debug("使用Agent脱敏后的文本内容进行请求");
                // 如果 Agent 没识别出东西，则回退到基础脱敏内容
                String content = (maskedContent != null) ? maskedContent : inputDesensitized.getDesensitizedContent();
                return client.sendRequest(content, config, processedParams);
            }
        } catch (Exception e) {
            log.error("调用LLM API失败", e);
            throw new RuntimeException("LLM API调用失败: " + e.getMessage(), e);
        }
    }

    // 为不同数据类型准备参数
    private Map<String, Object> prepareParamsForDataType(DesensitizationRequest inputRequest,
            DesensitizationResponse inputDesensitized,
            Map<String, Object> originalParams) {
        Map<String, Object> processedParams = new HashMap<>();
        if (originalParams != null) {
            processedParams.putAll(originalParams);
        }

        // 添加元数据信息
        processedParams.put("_data_type", inputRequest.getDataType());
        processedParams.put("_has_sensitive_info", !inputDesensitized.getDetectedEntities().isEmpty());
        processedParams.put("_sensitive_count", inputDesensitized.getDetectedEntities().size());

        // 根据数据类型添加特定参数
        if (inputRequest.isStructuredData()) {
            // 对于结构化数据，添加结构化标志
            processedParams.put("structured_data", true);
            // 添加结构化数据深度信息
            if (inputRequest.getStructuredData() != null) {
                processedParams.put("structured_depth", calculateObjectDepth(inputRequest.getStructuredData()));
            }
        } else if (inputRequest.isBinaryData()) {
            // 对于二进制数据，添加二进制标志
            processedParams.put("binary_data", true);
            if (inputRequest.getBinaryData() != null) {
                processedParams.put("binary_size", inputRequest.getBinaryData().length);
            }
        }

        // 移除敏感数据参数
        processedParams.remove("binaryData");
        processedParams.remove("structuredData");

        return processedParams;
    }

    // 计算对象深度（用于评估结构化数据的复杂度）
    private int calculateObjectDepth(Object obj) {
        if (obj == null) {
            return 0;
        }

        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            if (map.isEmpty()) {
                return 1;
            }
            return 1 + map.values().stream()
                    .mapToInt(this::calculateObjectDepth)
                    .max()
                    .orElse(0);
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            if (list.isEmpty()) {
                return 1;
            }
            return 1 + list.stream()
                    .mapToInt(this::calculateObjectDepth)
                    .max()
                    .orElse(0);
        }

        return 1; // 基本类型
    }

    // 为二进制数据生成提示
    private String generatePromptForBinaryData(DesensitizationRequest inputRequest,
            DesensitizationResponse inputDesensitized) {
        StringBuilder prompt = new StringBuilder();

        // 根据不同的数据类型生成不同的提示
        String dataType = inputRequest.getDataType() != null ? inputRequest.getDataType() : "二进制";

        prompt.append("# " + dataType + "数据分析任务\n\n");
        prompt.append("## 数据描述\n");
        prompt.append("- 数据类型: " + dataType + "\n");

        if (inputRequest.getBinaryData() != null) {
            prompt.append("- 数据大小: " + inputRequest.getBinaryData().length + " 字节\n");
        }

        prompt.append("\n## 数据内容\n");
        if (inputDesensitized.getDesensitizedContent() != null) {
            prompt.append(inputDesensitized.getDesensitizedContent());
        } else {
            prompt.append("[无法提取文本内容]");
        }

        // 添加用户原始提示（如果有）
        if (inputRequest.getContent() != null) {
            prompt.append("\n\n## 用户问题\n").append(inputRequest.getContent());
        }

        // 添加指令以确保回答的质量
        prompt.append("\n\n## 回答要求\n");
        prompt.append("1. 请基于提供的数据内容进行分析\n");
        prompt.append("2. 如果内容中包含敏感信息，请确保在回答中不直接引用\n");
        prompt.append("3. 请提供清晰、结构化的回答\n");

        return prompt.toString();
    }

    // 为结构化数据生成提示
    private String generatePromptForStructuredData(DesensitizationRequest inputRequest,
            DesensitizationResponse inputDesensitized) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("# 结构化数据分析任务\n\n");
        prompt.append("## 数据描述\n");
        prompt.append("- 数据类型: " + inputRequest.getDataType() + "\n");

        if (inputRequest.getStructuredData() != null) {
            prompt.append("- 字段数量: " + inputRequest.getStructuredData().size() + "\n");
        }

        prompt.append("\n## 数据内容\n");
        if (inputDesensitized.getDesensitizedContent() != null) {
            prompt.append("```json\n" + inputDesensitized.getDesensitizedContent() + "\n```\n");
        } else {
            prompt.append("[无法提取结构化内容]");
        }

        // 添加用户原始提示（如果有）
        if (inputRequest.getContent() != null) {
            prompt.append("\n\n## 用户问题\n").append(inputRequest.getContent());
        }

        // 添加指令以确保回答的质量
        prompt.append("\n\n## 回答要求\n");
        prompt.append("1. 请分析提供的结构化数据\n");
        prompt.append("2. 按照数据的结构和层级进行有条理的分析\n");
        prompt.append("3. 如需返回结构化结果，请保持与输入相似的数据结构\n");
        prompt.append("4. 确保回答中不包含任何敏感信息\n");

        return prompt.toString();
    }

    // JSON解析（使用Jackson）
    private Map<String, Object> parseJson(String jsonString) {
        try {
            if (jsonString == null || jsonString.trim().isEmpty() ||
                    "null".equals(jsonString.trim()) || "undefined".equals(jsonString.trim())) {
                return null;
            }

            // 使用Jackson解析JSON
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(jsonString,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.debug("JSON解析失败: {}", e.getMessage());
            throw new RuntimeException("Failed to parse JSON", e);
        }
    }

    // 内部类，用于封装脱敏结果
    private static class DesensitizationResult {
        private final String originalResponse;
        private final String desensitizedResponse;
        private final List<SensitiveEntity> inputEntities;
        private final List<SensitiveEntity> outputEntities;

        public DesensitizationResult(String originalResponse, String desensitizedResponse,
                List<SensitiveEntity> inputEntities, List<SensitiveEntity> outputEntities) {
            this.originalResponse = originalResponse;
            this.desensitizedResponse = desensitizedResponse;
            this.inputEntities = inputEntities;
            this.outputEntities = outputEntities;
        }

        public String getOriginalResponse() {
            return originalResponse;
        }

        public String getDesensitizedResponse() {
            return desensitizedResponse;
        }

        public List<SensitiveEntity> getInputEntities() {
            return inputEntities;
        }

        public List<SensitiveEntity> getOutputEntities() {
            return outputEntities;
        }
    }

}
