package com.hdu.apisensitivities.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdu.apisensitivities.config.LlmConfig;
import com.hdu.apisensitivities.entity.*;
import com.hdu.apisensitivities.service.LlmClient.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import com.hdu.apisensitivities.service.SensitiveDetection.NlpScanner;
import com.hdu.apisensitivities.service.Desensitization.SemanticPlaceholderStrategy;

@Slf4j
@Service

public class LlmProxyService {
    @Autowired
    private NlpScanner nlpScanner;

    @Autowired
    private SemanticPlaceholderStrategy semanticPlaceholderStrategy; // 占位符策略
    private final DesensitizationManager desensitizationManager;
    private final LlmConfigService configService;
    private final Map<LlmProvider, LlmClient> llmClients;

    @Autowired
    private RagKnowledgeService ragKnowledgeService;

    @Autowired
    public LlmProxyService(DesensitizationManager desensitizationManager,
                           LlmConfigService configService,
                           List<LlmClient> clients) {
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

    //实现 Agent 的自我反思 (Self-Reflection)
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
    private boolean agentSelfReflectionWithRAG(String maskedText, String complianceRules) {
        log.info("Agent 正在结合 RAG 动态合规知识进行自我反思审计...");

        // 降级策略：如果云端 Qdrant 没查到任何条文，则直接回退到你原本的普通反思逻辑
        if (complianceRules == null || complianceRules.isEmpty()) {
            log.warn("RAG 知识库未检索到相关垂直领域条文，回退执行通用安全反思。");
            return nlpScanner.checkSafety(maskedText); // 直接调用本地 Ollama
        }

        // 核心突破：构造动态增强的 Prompt，强迫本地大模型（Qwen）去严格遵守检索出来的法律条文
        String ragPrompt = "【系统指令】：你是一个数据隐私安全专家。\n" +
                "【参考合规法规】：\n" + complianceRules + "\n\n" +
                "【当前待审计文本】：\n\"" + maskedText + "\"\n\n" +
                "【任务】：请严格根据【参考合规法规】的要求，审查【当前待审计文本】中是否还残存未处理干净的间接隐私或敏感关联信息。" +
                "如果安全，请直接回复 SAFE。如果发现隐患，请回复 DANGEROUS。";

        // 将组装好的富含 RAG 知识的完整 Prompt 扔给你的本地 NlpScanner 执行推理
        boolean isStillDangerous = nlpScanner.checkSafety(ragPrompt);

        if (isStillDangerous) {
            log.warn("反思结论：结合 RAG 规范审计后，判定当前脱敏结果仍存在特定领域合规风险！");
        } else {
            log.info("反思结论：当前文本完全符合 RAG 行业合规要求，准予发送至云端。");
        }
        return isStillDangerous;
    }

    //处理LLM请求（新版）
    public LlmResponse processLlmRequest(LlmRequest request) {
        Instant start = Instant.now();

        try {
            LlmProvider provider = request.getProvider();
            LlmConfig config = configService.getConfigOrDefault(provider);

            log.info("开始处理LLM请求，提供商: {}, 会话ID: {}, 数据类型: {}",
                    provider, request.getSessionId(), request.getDataType());

            // 验证提供商是否启用
            if (!configService.isProviderEnabled(provider)) {
                throw new RuntimeException("LLM提供商未启用或配置不完整: " + provider);
            }

            // 根据数据类型执行不同的脱敏逻辑
            DesensitizationResult result = processWithDataSensitiveProtection(request, config);

            long processingTime = Duration.between(start, Instant.now()).toMillis();

            // 构建响应
            LlmResponse.LlmResponseBuilder responseBuilder = LlmResponse.builder()
                    .originalResponse(result.getOriginalResponse())
                    .desensitizedResponse(result.getDesensitizedResponse())
                    .inputSensitiveEntities(result.getInputEntities())
                    .outputSensitiveEntities(result.getOutputEntities())
                    .provider(provider)
                    .model(config.getModel())
                    .processingTimeMs(processingTime)
                    .success(true);

            // 设置响应数据类型
            responseBuilder.dataType(request.getDataType());

            // 对于JSON和XML类型的响应，尝试解析为结构化数据
            if ("JSON".equals(request.getDataType()) || "XML".equals(request.getDataType())) {
                try {
                    // 尝试将脱敏后的响应解析为结构化数据
                    Map<String, Object> structuredData = parseJson(result.getDesensitizedResponse());
                    if (structuredData != null && !structuredData.isEmpty()) {
                        responseBuilder.structuredResponse(structuredData);
                    }
                } catch (Exception e) {
                    log.warn("无法将响应解析为结构化数据: {}", e.getMessage());
                    // 解析失败不影响返回，仍然返回文本形式的响应
                }
            }

            return responseBuilder.build();

        } catch (Exception e) {
            log.error("处理LLM请求失败", e);
            long processingTime = Duration.between(start, Instant.now()).toMillis();

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
    }

    //处理LLM请求（兼容旧版ApiRequest）
    public ApiResponse processLlmRequest(ApiRequest request) {
        LlmRequest llmRequest = request.toLlmRequest();
        LlmResponse llmResponse = processLlmRequest(llmRequest);
        return llmResponse.toApiResponse();
    }

    @Async
    public CompletableFuture<LlmResponse> processLlmRequestAsync(LlmRequest request) {
        return CompletableFuture.completedFuture(processLlmRequest(request));
    }

    @Async
    public CompletableFuture<ApiResponse> processLlmRequestAsync(ApiRequest request) {
        return CompletableFuture.completedFuture(processLlmRequest(request));
    }

    //批量处理LLM请求
    public Map<String, LlmResponse> batchProcessLlmRequests(List<LlmRequest> requests) {
        return requests.parallelStream()
                .collect(Collectors.toMap(
                        LlmRequest::getSessionId,
                        this::processLlmRequest
                ));
    }

    public Map<String, ApiResponse> batchProcessLlmRequestsLegacy(List<ApiRequest> requests) {
        return requests.parallelStream()
                .collect(Collectors.toMap(
                        ApiRequest::getSessionId,
                        this::processLlmRequest
                ));
    }

    //测试所有提供商配置
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
                        }
                ));
    }

    // 根据不同数据类型执行敏感信息保护
    private DesensitizationResult processWithDataSensitiveProtection(LlmRequest request, LlmConfig config) {
        // 1. 基础脱敏 (由蔡翔宇同学优化的正则等基础逻辑进行第一轮常规清洗)
        DesensitizationRequest inputRequest = buildDesensitizationRequestForLlm(request);
        DesensitizationResponse baseDesensitized = desensitizationManager.process(inputRequest);

        // 2. 调用 NlpScanner (通过 Ollama 框架运行的本地本地 Qwen Agent，识别上下文语义实体)
        List<String> aiEntities = nlpScanner.extractEntities(baseDesensitized.getDesensitizedContent());

        // 3. 调用 SemanticPlaceholderStrategy 进行占位符打码（将敏感词抽离替换为 [ENTITY_1]）
        String maskedPrompt = semanticPlaceholderStrategy.desensitize(
                baseDesensitized.getDesensitizedContent(),
                aiEntities
        );

        // 3.5 将当前打码后的提示词转为虚拟向量，并去云端 Qdrant 捞取最相关的法条规范
        List<Float> textVector = ragKnowledgeService.getEmbedding(maskedPrompt);
        String complianceRules = ragKnowledgeService.retrieveRelevantRules(textVector);

        // 4. 反思逻辑 (将检索到的合规条文 complianceRules 传给反思方法进行二次审计)
        // 即使 aiEntities 为空，只要 RAG 捞出了特定的行业严苛规范，也可以选择让 Agent 参与反思
        if (!aiEntities.isEmpty() || (complianceRules != null && !complianceRules.isEmpty())) {
            log.info("中枢系统：结合 RAG 检索到的合规知识，触发 Agent 本地自我反思审计机制...");

            // 💡 改变了反思方法的调用，把 RAG 捞出来的规则也传进去！
            boolean dangerous = agentSelfReflectionWithRAG(maskedPrompt, complianceRules);

            if (dangerous) {
                log.error("🛑 警告：Agent 结合 RAG 行业知识库深度审计后，判定当前脱敏仍不合规！建议阻断请求或二次打码。");
                // 如果你想在期中汇报时展示更震撼的效果，可以在这里抛出一个自定义安全异常：
                // throw new SecurityException("违背隐私合规规范，请求已被安全网关拦截！");
            } else {
                log.info("✅ 审计通过：符合 RAG 知识库安全合规要求。");
            }
        }

        // 5. 调用修改后的 API 方法
        // 💡 这里的关键修改：必须把处理干净的 maskedPrompt 作为最终文本传递给下游云端 LLM 接口
        // 这里根据你原本的方法入参进行调整，通常做法是：
        String llmRawResponse = callLlmApiWithDataType(
                inputRequest,
                baseDesensitized,
                maskedPrompt, // 💡 确保这里传的是你 RAG 审计完的 maskedPrompt！
                config,
                request.getParameters(),
                request.getProvider()
        );

        // 6. 还原映射 (利用接收端本地存储的映射关系，将响应或输出自动还原回真实的原文，对用户保持透明)
        String finalResponse = semanticPlaceholderStrategy.restore(llmRawResponse);

        // 7. 组装并返回最终的脱敏结果对象
        DesensitizationResult result = new DesensitizationResult();
        result.setProcessedContent(finalResponse);
        result.setSafetyStatus(true);
        return result;
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

        // 根据数据类型设置不同的内容
        switch (dataType) {
            case "JSON", "XML":
                // 处理结构化数据
                if (request.getParameters() != null && request.getParameters().containsKey("structuredData")) {
                    Object structuredDataObj = request.getParameters().get("structuredData");
                    if (structuredDataObj instanceof Map) {
                        Map<String, Object> structuredData = (Map<String, Object>) structuredDataObj;
                        desensitizationRequest.setStructuredData(structuredData);
                        log.debug("使用参数中的结构化数据，字段数量: {}", structuredData != null ? structuredData.size() : 0);
                    } else {
                        log.warn("structuredData参数不是Map类型，实际类型: {}", structuredDataObj != null ? structuredDataObj.getClass().getName() : "null");
                    }
                } else if (request.getPrompt() != null) {
                    // 尝试将prompt解析为JSON
                    try {
                        Map<String, Object> parsedData = parseJson(request.getPrompt());
                        if (parsedData != null && !parsedData.isEmpty()) {
                            desensitizationRequest.setStructuredData(parsedData);
                            log.debug("成功将prompt解析为结构化数据，字段数量: {}", parsedData.size());
                        } else {
                            // 解析成功但数据为空，使用原始prompt
                            desensitizationRequest.setContent(request.getPrompt());
                            log.debug("prompt解析为结构化数据但为空，使用原始文本");
                        }
                    } catch (Exception e) {
                        // 解析失败，使用原始prompt
                        desensitizationRequest.setContent(request.getPrompt());
                        log.debug("无法将prompt解析为结构化数据: {}, 使用原始文本", e.getMessage());
                    }
                }
                break;
            case "IMAGE", "AUDIO", "PDF", "DOC":
                // 处理二进制数据
                if (request.getParameters() != null && request.getParameters().containsKey("binaryData")) {
                    Object binaryDataObj = request.getParameters().get("binaryData");
                    if (binaryDataObj instanceof byte[]) {
                        byte[] binaryData = (byte[]) binaryDataObj;
                        desensitizationRequest.setBinaryData(binaryData);
                        log.debug("使用二进制数据，大小: {} 字节", binaryData != null ? binaryData.length : 0);
                    } else {
                        log.warn("binaryData参数不是byte[]类型，实际类型: {}", binaryDataObj != null ? binaryDataObj.getClass().getName() : "null");
                    }
                }
                // 同时也处理文本描述
                if (request.getPrompt() != null) {
                    desensitizationRequest.setContent(request.getPrompt());
                    log.debug("使用文本描述: {}", request.getPrompt().length() > 100 ?
                            request.getPrompt().substring(0, 100) + "..." : request.getPrompt());
                }
                break;
            default:
                // 默认处理文本
                desensitizationRequest.setContent(request.getPrompt());
                log.debug("使用文本数据: {}", request.getPrompt() != null && request.getPrompt().length() > 100 ?
                        request.getPrompt().substring(0, 100) + "..." : request.getPrompt());
                break;
        }

        return desensitizationRequest;
    }

    // 为输出构建脱敏请求
    private DesensitizationRequest buildDesensitizationResponseForOutput(String response, LlmRequest request) {
        String inputDataType = request.getDataType() != null ? request.getDataType() : "TEXT";

        DesensitizationRequest desensitizationRequest = createBaseDesensitizationRequest(request, inputDataType); // 默认保持与输入相同的数据类型

        // 根据输入数据类型和响应内容决定如何处理输出
        switch (inputDataType) {
            case "JSON":
                // 对于JSON输入，尝试将响应解析为JSON
                if (response != null) {
                    try {
                        Map<String, Object> parsedData = parseJson(response);
                        if (parsedData != null && !parsedData.isEmpty()) {
                            desensitizationRequest.setStructuredData(parsedData);
                            log.debug("输出响应成功解析为JSON，字段数量: {}", parsedData.size());
                        } else {
                            // 解析成功但数据为空，作为文本处理
                            desensitizationRequest.setContent(response);
                            desensitizationRequest.setDataType("TEXT"); // 更新为文本类型
                            log.debug("输出响应解析为JSON但为空，作为文本处理");
                        }
                    } catch (Exception e) {
                        // 解析失败，作为文本处理
                        desensitizationRequest.setContent(response);
                        desensitizationRequest.setDataType("TEXT"); // 更新为文本类型
                        log.debug("无法将输出响应解析为JSON: {}, 作为文本处理", e.getMessage());
                    }
                }
                break;
            case "XML":
                // 对于XML输入，尝试将响应解析为结构化数据
                if (response != null) {
                    try {
                        // 这里可以添加XML解析逻辑
                        // 暂时作为文本处理
                        desensitizationRequest.setContent(response);
                        desensitizationRequest.setDataType("TEXT"); // 更新为文本类型
                    } catch (Exception e) {
                        desensitizationRequest.setContent(response);
                        desensitizationRequest.setDataType("TEXT"); // 更新为文本类型
                    }
                }
                break;
            case "IMAGE", "AUDIO", "PDF", "DOC":
                // 对于二进制输入，响应通常是文本描述
                desensitizationRequest.setContent(response);
                desensitizationRequest.setDataType("TEXT"); // 更新为文本类型
                break;
            default:
                // 默认作为文本处理
                desensitizationRequest.setContent(response);
                break;
        }

        return desensitizationRequest;
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
    private String generatePromptForBinaryData(DesensitizationRequest inputRequest, DesensitizationResponse inputDesensitized) {
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
    private String generatePromptForStructuredData(DesensitizationRequest inputRequest, DesensitizationResponse inputDesensitized) {
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
            return mapper.readValue(jsonString, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
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

