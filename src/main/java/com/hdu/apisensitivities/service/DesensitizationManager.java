package com.hdu.apisensitivities.service;

import com.hdu.apisensitivities.service.DataParser.DataParserManager;
import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.ScenarioPerception.ScenarioAnalysisResult;
import com.hdu.apisensitivities.service.Desensitization.DesensitizationStrategy;
import com.hdu.apisensitivities.service.Desensitization.DesensitizeRequestContext;
import com.hdu.apisensitivities.service.SensitiveDetection.TextSensitiveDetectionService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 脱敏管理器，负责处理敏感信息的完整脱敏流程。
 * <p>
 * 核心处理流程包括：
 * <ol>
 * <li>数据解析：根据数据类型（文本、JSON、二进制等）提取统一文本内容</li>
 * <li>情景分析：识别请求上下文（如医疗、金融、通用），动态调整检测范围</li>
 * <li>敏感信息检测：识别文本中的敏感实体（如身份证、手机号、邮箱等）</li>
 * <li>脱敏策略执行：根据配置或实体类型选择合适的脱敏算法（替换、遮盖、加密等）</li>
 * </ol>
 * </p>
 * <p>
 * 支持黑白名单过滤、手动情景覆盖、自动/LLM 情景感知等高级特性。
 * </p>
 */
@Slf4j
@Service
public class DesensitizationManager {
    private static final String DEFAULT_TEXT_STRATEGY_NAME = "maskDesensitizationStrategy";

    private final TextSensitiveDetectionService detectionService;
    private final List<DesensitizationStrategy> strategies;
    private final DataParserManager dataParserManager;

    /**
     * 构造脱敏管理器实例。
     *
     * @param detectionService  敏感信息检测服务，用于识别文本中的敏感实体
     * @param strategies        所有可用的脱敏策略实现，将根据上下文自动选择
     * @param dataParserManager 数据解析管理器，负责将不同格式（JSON、XML、二进制等）转换为统一文本
     */
    public DesensitizationManager(TextSensitiveDetectionService detectionService,
            List<DesensitizationStrategy> strategies,
            DataParserManager dataParserManager) {
        this.detectionService = detectionService;
        this.strategies = strategies;
        this.dataParserManager = dataParserManager;
    }

    /**
     * 处理脱敏请求，执行完整的数据解析、情景分析、敏感检测和脱敏流程。
     * <p>
     * 处理步骤：
     * <ol>
     * <li>根据请求中的数据类型（TEXT/JSON/XML/IMAGE 等）调用 {@link DataParserManager} 提取文本内容</li>
     * <li>若开启自动情景感知，则根据配置选择关键词或 LLM 服务分析场景，并调整敏感类型检测范围</li>
     * <li>调用 {@link TextSensitiveDetectionService} 检测文本中的敏感实体</li>
     * <li>选择合适的脱敏策略并执行脱敏</li>
     * <li>封装并返回脱敏结果</li>
     * </ol>
     * </p>
     *
     * @param request 脱敏请求，包含原始数据、数据类型、黑白名单、情景配置等
     * @return 脱敏响应，包含原始内容、脱敏后内容、检测到的敏感实体、处理状态及错误信息
     */
    public DesensitizationResponse process(DesensitizationRequest request) {
        try {
            initializeSessionContext(request);

            String dataType = request.getDataType();
            log.info("处理请求，数据类型: {}", dataType);

            String parsedContent = parseRequestContent(request);
            if (parsedContent == null || parsedContent.isEmpty()) {
                log.warn("解析后内容为空，可能是数据格式不支持或内容无效");
                return buildFailedResponse(request, "数据解析失败：无法提取有效内容");
            }

            request.setContent(parsedContent);
            log.info("数据解析完成，提取到 {} 个字符的文本内容", parsedContent.length());

            ScenarioAnalysisResult scenarioResult = prepareDetectionScopeForCurrentMode(request);

            List<SensitiveEntity> entities = detectSensitiveEntities(request, scenarioResult);
            DesensitizationResult result = applyDesensitization(request, entities);
            return buildSuccessResponse(result, entities);

        } catch (Exception e) {
            log.error("脱敏处理失败", e);
            return buildFailedResponse(request, "脱敏处理失败: " + e.getMessage());
        } finally {
            DesensitizeRequestContext.clear();
        }
    }

    private void initializeSessionContext(DesensitizationRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "SESSION_" + Math.abs(request.getMainContent().hashCode());
        }
        DesensitizeRequestContext.setSessionId(sessionId);
    }

    private String parseRequestContent(DesensitizationRequest request) throws Exception {
        return dataParserManager.parseData(request);
    }

    private ScenarioAnalysisResult prepareDetectionScopeForCurrentMode(DesensitizationRequest request) {
        // 当前项目默认关闭情景感知，统一放开检测范围，便于规则匹配和回归测试。
        request.setIncludeTypes(null);
        request.setStrictMode(false);
        log.info("情景感知已完全禁用，将检测所有敏感类型，严格模式关闭");
        return null;
    }

    private DesensitizationResponse buildSuccessResponse(DesensitizationResult result, List<SensitiveEntity> entities) {
        return new DesensitizationResponse(
                result.getOriginalContent(),
                result.getDesensitizedContent(),
                entities,
                true,
                "脱敏处理成功");
    }

    private DesensitizationResponse buildFailedResponse(DesensitizationRequest request, String errorMessage) {
        String originalContent = request.getMainContent() != null ? request.getMainContent() : "";
        return new DesensitizationResponse(
                originalContent,
                originalContent,
                Collections.emptyList(),
                false,
                errorMessage);
    }

    // 敏感信息检测
    private List<SensitiveEntity> detectSensitiveEntities(DesensitizationRequest request,
            ScenarioAnalysisResult scenarioResult) {
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

        entities = resolveOverlappingEntities(entities);

        log.info("检测完成，类型: {}, 发现 {} 个敏感实体",
                request.getDataType() != null ? request.getDataType() : "TEXT",
                entities.size());

        return entities;
    }

    // 敏感信息脱敏
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

    // 智能选择策略
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
            Optional<DesensitizationStrategy> preferredStrategy = findPreferredStrategyForDataType(dataType);
            if (preferredStrategy.isPresent()) {
                return preferredStrategy.get();
            }

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

    private List<SensitiveEntity> resolveOverlappingEntities(List<SensitiveEntity> entities) {
        if (entities == null || entities.size() <= 1) {
            return entities == null ? Collections.emptyList() : entities;
        }

        List<SensitiveEntity> sortedEntities = new ArrayList<>(entities);
        sortedEntities.sort(Comparator.comparingInt(SensitiveEntity::getStart)
                .thenComparingInt(entity -> entity.getEnd() - entity.getStart()));

        List<SensitiveEntity> resolved = new ArrayList<>();
        for (SensitiveEntity candidate : sortedEntities) {
            if (resolved.isEmpty()) {
                resolved.add(candidate);
                continue;
            }

            SensitiveEntity last = resolved.get(resolved.size() - 1);
            if (!isOverlapping(last, candidate)) {
                resolved.add(candidate);
                continue;
            }

            if (preferCandidateOverExisting(last, candidate)) {
                resolved.set(resolved.size() - 1, candidate);
            }
        }

        return resolved;
    }

    private boolean isOverlapping(SensitiveEntity left, SensitiveEntity right) {
        return left.getStart() < right.getEnd() && right.getStart() < left.getEnd();
    }

    private boolean preferCandidateOverExisting(SensitiveEntity existing, SensitiveEntity candidate) {
        // 地址内包含的 PERSON/ORG 碎片：当 ADDRESS 跨度 >= 3 倍时，抑制内部碎片
        if (isAddressEnclosingFragment(existing, candidate) || isAddressEnclosingFragment(candidate, existing)) {
            SensitiveEntity addr = existing.getType() == SensitiveType.ADDRESS ? existing : candidate;
            SensitiveEntity frag = addr == existing ? candidate : existing;
            int addrSpan = addr.getEnd() - addr.getStart();
            int fragSpan = frag.getEnd() - frag.getStart();
            if (addrSpan >= fragSpan * 3) {
                return existing.getType() != SensitiveType.ADDRESS;
            }
        }

        int typeCmp = typeSpecificityScore(candidate.getType()) - typeSpecificityScore(existing.getType());
        if (typeCmp != 0) {
            return typeCmp > 0;
        }

        int existingSpan = existing.getEnd() - existing.getStart();
        int candidateSpan = candidate.getEnd() - candidate.getStart();
        if (candidateSpan != existingSpan) {
            return candidateSpan < existingSpan;
        }

        int confidenceCompare = Double.compare(candidate.getConfidence(), existing.getConfidence());
        if (confidenceCompare != 0) {
            return confidenceCompare > 0;
        }

        return sensitiveTypePriority(candidate.getType()) > sensitiveTypePriority(existing.getType());
    }

    private boolean isAddressEnclosingFragment(SensitiveEntity a, SensitiveEntity b) {
        return a.getType() == SensitiveType.ADDRESS
                && a.getStart() <= b.getStart() && a.getEnd() >= b.getEnd()
                && (b.getType() == SensitiveType.PERSON || b.getType() == SensitiveType.ORGANIZATION);
    }

    private int typeSpecificityScore(SensitiveType type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case ID_CARD, BANK_CARD, CREDIT_CARD, EMAIL, IP_ADDRESS -> 5;
            case PHONE_NUMBER, API_KEY -> 4;
            case NAME, PERSON -> 3;
            case ADDRESS, ORGANIZATION -> 2;
            case PASSWORD -> 1;
            default -> 0;
        };
    }

    private int sensitiveTypePriority(SensitiveType type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case ID_CARD, PHONE_NUMBER, BANK_CARD, CREDIT_CARD, EMAIL, PASSWORD, API_KEY -> 4;
            case NAME, PERSON -> 3;
            case ADDRESS, ORGANIZATION -> 2;
            default -> 1;
        };
    }

    private Optional<DesensitizationStrategy> findPreferredStrategyForDataType(String dataType) {
        if (dataType == null) {
            return Optional.empty();
        }

        String normalizedDataType = dataType.toUpperCase(Locale.ROOT);
        if (!Set.of("TEXT", "JSON", "XML").contains(normalizedDataType)) {
            return Optional.empty();
        }

        return strategies.stream()
                .filter(strategy -> DEFAULT_TEXT_STRATEGY_NAME.equals(strategy.getName()))
                .filter(strategy -> strategy.supportsDataType(normalizedDataType))
                .findFirst();
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
