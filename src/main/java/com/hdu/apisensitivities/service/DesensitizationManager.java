package com.hdu.apisensitivities.service;

import com.hdu.apisensitivities.service.DataParser.DataParserManager;
import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.ScenarioPerception.ScenarioAnalysisResult;
import com.hdu.apisensitivities.service.ScenarioPerception.ScenarioPerceptionService;
import com.hdu.apisensitivities.service.SensitiveDetection.SensitiveDetectionService;
import com.hdu.apisensitivities.service.Desensitization.DesensitizationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DesensitizationManager {

    private final SensitiveDetectionService detectionService;
    private final List<DesensitizationStrategy> strategies;
    private final Set<String> blacklist;
    private final Set<String> whitelist;
    private final DataParserManager dataParserManager;
    private final ScenarioPerceptionService scenarioPerceptionService;
    private final ScenarioPerceptionService llmScenarioPerceptionService;

    @Autowired
    public DesensitizationManager(SensitiveDetectionService detectionService,
                                  List<DesensitizationStrategy> strategies,
                                  DataParserManager dataParserManager,
                                  @org.springframework.beans.factory.annotation.Qualifier("keywordBasedScenarioPerceptionService") ScenarioPerceptionService scenarioPerceptionService,
                                  @org.springframework.beans.factory.annotation.Qualifier("llmScenarioPerceptionService") ScenarioPerceptionService llmScenarioPerceptionService) {
        this.detectionService = detectionService;
        this.strategies = strategies;
        this.blacklist = loadBlacklist();
        this.whitelist = loadWhitelist();
        this.dataParserManager = dataParserManager;
        this.scenarioPerceptionService = scenarioPerceptionService;
        this.llmScenarioPerceptionService = llmScenarioPerceptionService;
    }

    //处理脱敏请求
    public DesensitizationResponse process(DesensitizationRequest request) {
        try {
            // ========== 步骤1：数据解析 ==========
            // 记录请求的数据类型
            String dataType = request.getDataType();
            log.info("处理请求，数据类型: {}", dataType);

            // 数据预处理：根据数据类型使用DataParserManager进行解析
            String parsedContent = dataParserManager.parseData(request);

            if (parsedContent == null || parsedContent.isEmpty()) {
                log.warn("解析后内容为空，可能是数据格式不支持或内容无效");
                String originalContent = request.getMainContent() != null ? request.getMainContent() : "";
                return new DesensitizationResponse(
                        originalContent,
                        originalContent,
                        Collections.emptyList(),
                        false,
                        "数据解析失败：无法提取有效内容"
                );
            }

            // 保存解析后的内容到请求对象中，供后续处理使用
            request.setContent(parsedContent);
            log.info("数据解析完成，提取到 {} 个字符的文本内容", parsedContent.length());

            // ========== 步骤2：情景分析 ==========
            // 情景分析：根据用户设置决定是否进行自动情景感知
            ScenarioAnalysisResult scenarioResult;
            if (request.isAutoScenarioDetection()) {
                // 判断使用哪种情景感知服务
                // 如果请求中指定了使用LLM分析，则优先使用LLM服务
                boolean useLlm = request.getMetadata() != null &&
                               "true".equalsIgnoreCase(String.valueOf(request.getMetadata().get("useLlmScenario")));

                if (useLlm) {
                    log.info("使用LLM进行情景分析...");
                    scenarioResult = llmScenarioPerceptionService.analyzeScenario(request);
                } else {
                    // 默认使用关键词匹配，速度快且成本低
                    scenarioResult = scenarioPerceptionService.analyzeScenario(request);
                }

                // 检查用户是否手动指定了情景类型
                if (request.getManualScenarioType() != null && !request.getManualScenarioType().isEmpty()) {
                    // 使用用户手动指定的情景类型
                    try {
                        ScenarioAnalysisResult.ScenarioType manualType =
                            ScenarioAnalysisResult.ScenarioType.valueOf(request.getManualScenarioType().toUpperCase());
                        scenarioResult.setScenarioType(manualType);
                        scenarioResult.setConfidence(1.0); // 手动指定的情景置信度为1.0
                        log.info("使用用户手动指定的情景类型: {}", manualType);
                    } catch (IllegalArgumentException e) {
                        log.warn("用户手动指定的情景类型无效: {}, 使用自动识别的情景类型",
                                request.getManualScenarioType());
                    }
                }

                log.info("情景分析完成，情景类型: {}, 置信度: {:.2f}",
                        scenarioResult.getScenarioType(), scenarioResult.getConfidence());

                // 根据分析服务类型调整检测范围（因为不同服务的adjustDetectionScope逻辑可能不同）
                if (useLlm) {
                    llmScenarioPerceptionService.adjustDetectionScope(request, scenarioResult);
                } else {
                    scenarioPerceptionService.adjustDetectionScope(request, scenarioResult);
                }
            } else {
                // 自动情景感知关闭，使用默认情景
                scenarioResult = scenarioPerceptionService.getDefaultScenario();
                scenarioPerceptionService.adjustDetectionScope(request, scenarioResult);
                log.info("自动情景感知已关闭，使用默认情景类型: {}", scenarioResult.getScenarioType());
            }

            // ========== 步骤3：敏感信息检测 ==========
            // 执行敏感信息检测（使用解析后的统一文本内容）
            List<SensitiveEntity> entities = detectSensitiveEntities(request, scenarioResult);

            // 根据请求的黑白名单过滤实体
            entities = filterEntities(entities, request);

            // 根据情景分析结果进一步过滤敏感实体
            entities = filterEntitiesByScenario(entities, scenarioResult);
            log.info("敏感实体过滤完成，剩余 {} 个实体", entities.size());

            // ========== 步骤4：选择脱敏策略并执行 ==========
            // 执行脱敏处理（使用解析后的统一文本内容）
            DesensitizationResult result = applyDesensitization(request, entities);

            // 构建响应
            return new DesensitizationResponse(
                    result.getOriginalContent(),
                    result.getDesensitizedContent(),
                    entities,
                    true,
                    "脱敏处理成功"
            );

        } catch (Exception e) {
            log.error("脱敏处理失败", e);
            String originalContent = request.getMainContent() != null ? request.getMainContent() : "";
            return new DesensitizationResponse(
                    originalContent,
                    originalContent,
                    Collections.emptyList(),
                    false,
                    "脱敏处理失败: " + e.getMessage()
            );
        }
    }

    //敏感信息检测
    private List<SensitiveEntity> detectSensitiveEntities(DesensitizationRequest request, ScenarioAnalysisResult scenarioResult) {
        List<SensitiveEntity> entities = new ArrayList<>();
        // 已经在process方法中通过dataParserManager解析了所有类型的数据
        // 直接使用解析后的文本内容进行敏感信息检测
        if (request.getContent() != null) {
            // 使用请求中的includeTypes字段进行敏感信息检测，并传入情景分析结果
            entities = detectionService.detectSensitiveInfo(
                    request.getContent(),
                    request.getLanguage(),
                    request.getIncludeTypes(),
                    scenarioResult);
        }

        log.info("检测完成，类型: {}, 发现 {} 个敏感实体",
                request.getDataType() != null ? request.getDataType() : "TEXT",
                entities.size());

        return entities;
    }

    //敏感信息脱敏
    private DesensitizationResult applyDesensitization(DesensitizationRequest request, List<SensitiveEntity> entities) {
        if (entities.isEmpty()) {
            return new DesensitizationResult(
                    request.getContent(),
                    request.getContent());
        }

        // 根据数据类型和指定策略选择合适的脱敏策略
        DesensitizationStrategy strategy = selectStrategy(request, entities);

        // 已经在process方法中通过dataParserManager解析了所有类型的数据
        // 直接对解析后的文本内容进行脱敏处理
        String desensitizedContent = strategy.desensitize(
                request.getContent(), entities);

        return new DesensitizationResult(
                request.getContent(),
                desensitizedContent);
    }
    //智能选择策略
    private DesensitizationStrategy selectStrategy(DesensitizationRequest request, List<SensitiveEntity> entities) {
        String requestedStrategy = request.getStrategy();
        String dataType = request.getDataType();

        // 1. 如果请求指定了策略，优先使用
        if (requestedStrategy != null) {
            Optional<DesensitizationStrategy> strategy = strategies.stream()
                    .filter(s -> s.getName().equals(requestedStrategy) &&
                               (dataType == null || s.supportsDataType(dataType)))
                    .findFirst();
            if (strategy.isPresent()) {
                return strategy.get();
            }
        }

        // 2. 根据数据类型选择支持的策略
        if (dataType != null) {
            Optional<DesensitizationStrategy> strategy = strategies.stream()
                    .filter(s -> s.supportsDataType(dataType))
                    .findFirst();
            if (strategy.isPresent()) {
                return strategy.get();
            }
        }

        // 3. 回退到基于敏感类型选择策略
        Set<SensitiveType> types = entities.stream()
                .map(SensitiveEntity::getType)
                .collect(Collectors.toSet());

        return strategies.stream()
                .filter(s -> s.supportedTypes().containsAll(types))
                .findFirst()
                .orElse(strategies.get(0)); // 默认使用第一个策略
    }

    //根据情景分析结果过滤敏感实体
    private List<SensitiveEntity> filterEntitiesByScenario(List<SensitiveEntity> entities,
            ScenarioAnalysisResult scenarioResult) {
        if (scenarioResult == null) {
            return entities;
        }

        List<SensitiveEntity> filteredEntities = entities.stream()
                .filter(entity -> scenarioResult.shouldIncludeType(entity.getType().name()))
                .collect(java.util.stream.Collectors.toList());

        if (filteredEntities.size() != entities.size()) {
            log.info("根据情景过滤敏感实体，过滤前: {} 个，过滤后: {} 个",
                    entities.size(), filteredEntities.size());
        }

        return filteredEntities;
    }

    // TODO: 这里黑白名单有点影响，测试数据不少会直接被黑白名单过滤

    // 黑名单：这些内容一定要脱敏
    private Set<String> loadBlacklist() {
        return Set.of("secret_key", "password", "token", "private_key", "auth_key");
    }

    // 白名单：这些内容不需要脱敏（公开的示例数据）
    private Set<String> loadWhitelist() {
        return Set.of("example@example.com", "400-123-4567", "test@test.com");
    }

    private List<SensitiveEntity> filterEntities(List<SensitiveEntity> entities, DesensitizationRequest request) {
        return entities.stream()
                .filter(entity -> !isInBlacklist(entity.getOriginalText(), request))
                .filter(entity -> !isInWhitelist(entity.getOriginalText(), request))
                .collect(Collectors.toList());
    }

    private boolean isInBlacklist(String text, DesensitizationRequest request) {
        Set<String> effectiveBlacklist = new HashSet<>(blacklist);
        if (request.getBlacklist() != null) {
            effectiveBlacklist.addAll(request.getBlacklist());
        }
        return effectiveBlacklist.stream().anyMatch(text::contains);
    }

    private boolean isInWhitelist(String text, DesensitizationRequest request) {
        Set<String> effectiveWhitelist = new HashSet<>(whitelist);
        if (request.getWhitelist() != null) {
            effectiveWhitelist.addAll(request.getWhitelist());
        }
        return effectiveWhitelist.stream().anyMatch(text::contains);
    }

    // 内部类，用于封装脱敏结果
    private static class DesensitizationResult {
        private final String originalContent;
        private final String desensitizedContent;

        public DesensitizationResult(String originalContent, String desensitizedContent) {
            this.originalContent = originalContent;
            this.desensitizedContent = desensitizedContent;
        }

        public String getOriginalContent() {
            return originalContent;
        }

        public String getDesensitizedContent() {
            return desensitizedContent;
        }
    }

}