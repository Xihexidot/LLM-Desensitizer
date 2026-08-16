package com.hdu.apisensitivities.controller;

import com.hdu.apisensitivities.dto.ConfirmActionRequest;
import com.hdu.apisensitivities.dto.PluginAgentStatusResponse;
import com.hdu.apisensitivities.dto.PluginCheckRequest;
import com.hdu.apisensitivities.dto.PluginCheckResponse;
import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.gateway.GatewayAuditEvent;
import com.hdu.apisensitivities.entity.gateway.GatewayDecisionAction;
import com.hdu.apisensitivities.entity.gateway.GatewayRiskLevel;
import com.hdu.apisensitivities.entity.gateway.GatewayRiskDecision;
import com.hdu.apisensitivities.repository.GatewayAuditRepository;
import com.hdu.apisensitivities.service.DesensitizationManager;
import com.hdu.apisensitivities.service.PluginAgentReviewService;
import com.hdu.apisensitivities.service.SensitiveDetection.NlpScanner;
import com.hdu.apisensitivities.service.gateway.RiskScorer;
import com.hdu.apisensitivities.controller.RiskPolicyController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/plugin")
public class PluginAuditController {

    private final DesensitizationManager desensitizationManager;
    private final GatewayAuditRepository auditRepository;
    private final PluginAgentReviewService pluginAgentReviewService;
    private final NlpScanner nlpScanner;

    public PluginAuditController(DesensitizationManager desensitizationManager,
            GatewayAuditRepository auditRepository,
            PluginAgentReviewService pluginAgentReviewService,
            NlpScanner nlpScanner) {
        this.desensitizationManager = desensitizationManager;
        this.auditRepository = auditRepository;
        this.pluginAgentReviewService = pluginAgentReviewService;
        this.nlpScanner = nlpScanner;
    }

    @GetMapping("/agent/status")
    public ResponseEntity<PluginAgentStatusResponse> agentStatus() {
        NlpScanner.AgentStatus status = nlpScanner.getStatus();
        return ResponseEntity.ok(PluginAgentStatusResponse.builder()
                .enabled(status.enabled())
                .reachable(status.reachable())
                .mode(status.mode())
                .endpoint(status.endpoint())
                .model(status.model())
                .message(status.message())
                .build());
    }

    @PostMapping("/audit-check")
    public ResponseEntity<PluginCheckResponse> auditCheck(@RequestBody PluginCheckRequest req) {
        DesensitizationRequest desensitizationRequest = new DesensitizationRequest();
        desensitizationRequest.setContent(req.getContent() != null ? req.getContent() : "");
        desensitizationRequest.setDataType(req.getDataType() != null ? req.getDataType() : "TEXT");
        desensitizationRequest.setStrictMode(req.isStrictMode());
        desensitizationRequest.setAutoScenarioDetection(req.isAutoScenarioDetection());

        DesensitizationResponse result = desensitizationManager.process(desensitizationRequest);
        PluginAgentReviewService.AgentReviewResult agentReview = pluginAgentReviewService.review(req.getContent(),
                result);
        List<SensitiveEntity> mergedEntities = mergeEntities(result.getDetectedEntities(), agentReview.agentEntities());
        String finalContent = agentReview.desensitizedContent() != null
                ? agentReview.desensitizedContent()
                : result.getDesensitizedContent();
        Map<String, String> mergedMaskMapping = mergeMaskMappings(result.getMaskMapping(), agentReview.maskMapping());

        String eventId = "evt-" + UUID.randomUUID();
        List<String> matchedTypes = extractTypes(mergedEntities);

        // 基于策略配置中心计算风险等级与决策动作
        GatewayRiskDecision riskDecision = buildPluginRiskDecision(matchedTypes);
        riskDecision = applyAgentRiskDecision(riskDecision, agentReview);

        GatewayAuditEvent event = GatewayAuditEvent.builder()
                .eventId(eventId)
                .timestamp(Instant.now())
                .userId(req.getUserId())
                .department(req.getDepartment())
                .channel("BROWSER_PLUGIN")
                .requestType("PLUGIN_CHECK")
                .targetProvider(req.getTargetProvider())
                .matchedSensitiveTypes(matchedTypes)
                .decisionAction(riskDecision.getDecisionAction())
                .inputRiskLevel(riskDecision.getRiskLevel())
                .outputRiskLevel(GatewayRiskLevel.NONE)
                .originalContent(req.getContent())
                .processedContent(finalContent)
                .requestHash(hash(req.getContent()))
                .build();
        auditRepository.save(event);

        return ResponseEntity.ok(PluginCheckResponse.builder()
                .detectedEntities(mergedEntities)
                .desensitizedContent(finalContent)
                .auditEventId(eventId)
                .riskLevel(riskDecision.getRiskLevel())
                .decisionAction(riskDecision.getDecisionAction())
                .detectionMode(agentReview.mode())
                .agentEnabled(agentReview.enabled())
                .agentAvailable(agentReview.available())
                .agentUsed(agentReview.used())
                .agentEndpoint(agentReview.endpoint())
                .agentModel(agentReview.model())
                .agentMessage(agentReview.message())
                .agentSemanticEntities(agentReview.semanticEntities())
                .maskMapping(mergedMaskMapping)
                .build());
    }

    /**
     * 依据策略配置中心计算插件侧的风险等级与决策动作。
     * 与 API 网关侧 buildRiskDecision 逻辑一致，供插件弹窗按等级控制按钮。
     */
    private GatewayRiskDecision buildPluginRiskDecision(List<String> matchedTypes) {
        RiskPolicyController.PolicyConfig config = RiskPolicyController.getCurrentConfig();
        String defaultAction = config.global != null ? config.global.defaultAction : "DESENSITIZE_AND_ALLOW";
        int maxCount = config.global != null ? config.global.maxSensitiveCount : 5;

        // 无敏感信息 → 直接放行
        if (matchedTypes == null || matchedTypes.isEmpty()) {
            return GatewayRiskDecision.builder()
                    .riskLevel(GatewayRiskLevel.NONE).decisionAction(GatewayDecisionAction.ALLOW)
                    .matchedTypes(List.of()).matchedRules(List.of())
                    .policyId("policy-default").policyVersion("skeleton-v1")
                    .build();
        }

        // 超出最大敏感类型数量 → 强制阻断
        if (matchedTypes.size() > maxCount) {
            return GatewayRiskDecision.builder()
                    .riskLevel(GatewayRiskLevel.HIGH).decisionAction(GatewayDecisionAction.BLOCK)
                    .matchedTypes(matchedTypes).matchedRules(List.of())
                    .policyId("policy-default").policyVersion("skeleton-v1")
                    .build();
        }

        // 排查匹配到的场景策略，取匹配类型的最高风险等级动作
        GatewayDecisionAction finalAction = GatewayDecisionAction.DESENSITIZE_AND_ALLOW;
        GatewayRiskLevel finalRiskLevel = GatewayRiskLevel.LOW;

        if (config.scenes != null) {
            for (RiskPolicyController.ScenePolicy scene : config.scenes) {
                if (!scene.enabled || scene.types == null || scene.types.isEmpty())
                    continue;
                boolean sceneHit = matchedTypes.stream().anyMatch(t -> scene.types.contains(t.toUpperCase()));
                if (!sceneHit)
                    continue;

                GatewayRiskLevel sceneRisk = switch (scene.riskLevel != null ? scene.riskLevel.toUpperCase() : "LOW") {
                    case "HIGH" -> GatewayRiskLevel.HIGH;
                    case "MEDIUM" -> GatewayRiskLevel.MEDIUM;
                    default -> GatewayRiskLevel.LOW;
                };
                if (sceneRisk.ordinal() > finalRiskLevel.ordinal()) {
                    finalRiskLevel = sceneRisk;
                }
                GatewayDecisionAction sceneAction = switch (scene.action != null ? scene.action.toUpperCase()
                        : "DESENSITIZE_AND_ALLOW") {
                    case "BLOCK" -> GatewayDecisionAction.BLOCK;
                    case "ALLOW" -> GatewayDecisionAction.ALLOW;
                    default -> GatewayDecisionAction.DESENSITIZE_AND_ALLOW;
                };
                if (sceneAction.ordinal() > finalAction.ordinal()) {
                    finalAction = sceneAction;
                }
            }
        }

        // 未命中任何场景策略 → 使用全局默认
        if (finalAction == GatewayDecisionAction.DESENSITIZE_AND_ALLOW && finalRiskLevel == GatewayRiskLevel.LOW
                && matchedTypes.size() <= 2) {
            // 风险评分兜底
            int[] scoreResult = new int[2];
            RiskScorer.scoreWithLevel(matchedTypes, scoreResult);
            finalRiskLevel = switch (scoreResult[1]) {
                case 4 -> GatewayRiskLevel.CRITICAL;
                case 3 -> GatewayRiskLevel.HIGH;
                case 2 -> GatewayRiskLevel.MEDIUM;
                default -> GatewayRiskLevel.LOW;
            };
            // 严重类型 → 禁止发送原文
            if (finalRiskLevel == GatewayRiskLevel.HIGH || finalRiskLevel == GatewayRiskLevel.CRITICAL) {
                finalAction = GatewayDecisionAction.BLOCK;
            } else {
                GatewayDecisionAction globalAction = switch (defaultAction.toUpperCase()) {
                    case "BLOCK" -> GatewayDecisionAction.BLOCK;
                    case "ALLOW" -> GatewayDecisionAction.ALLOW;
                    default -> GatewayDecisionAction.DESENSITIZE_AND_ALLOW;
                };
                finalAction = globalAction;
            }
        }

        return GatewayRiskDecision.builder()
                .riskLevel(finalRiskLevel).decisionAction(finalAction)
                .matchedTypes(matchedTypes).matchedRules(List.of())
                .policyId("policy-default").policyVersion("skeleton-v1")
                .build();
    }

    @PostMapping("/confirm-action")
    public ResponseEntity<Void> confirmAction(@RequestBody ConfirmActionRequest req) {
        if (req.getAuditEventId() != null && req.getUserAction() != null) {
            auditRepository.updateUserAction(req.getAuditEventId(), req.getUserAction());
        }
        return ResponseEntity.ok().build();
    }

    private GatewayRiskDecision applyAgentRiskDecision(GatewayRiskDecision currentDecision,
            PluginAgentReviewService.AgentReviewResult agentReview) {
        if (agentReview == null) {
            return currentDecision;
        }

        GatewayRiskLevel riskLevel = currentDecision.getRiskLevel();
        GatewayDecisionAction decisionAction = currentDecision.getDecisionAction();

        if (agentReview.used() && !agentReview.agentEntities().isEmpty()
                && riskLevel.ordinal() < GatewayRiskLevel.MEDIUM.ordinal()) {
            riskLevel = GatewayRiskLevel.MEDIUM;
            if (decisionAction.ordinal() < GatewayDecisionAction.DESENSITIZE_AND_ALLOW.ordinal()) {
                decisionAction = GatewayDecisionAction.DESENSITIZE_AND_ALLOW;
            }
        }

        if (agentReview.dangerous()) {
            riskLevel = GatewayRiskLevel.HIGH;
            decisionAction = GatewayDecisionAction.BLOCK;
        }

        return GatewayRiskDecision.builder()
                .riskLevel(riskLevel)
                .decisionAction(decisionAction)
                .matchedTypes(currentDecision.getMatchedTypes())
                .matchedRules(currentDecision.getMatchedRules())
                .policyId(currentDecision.getPolicyId())
                .policyVersion(currentDecision.getPolicyVersion())
                .routeTarget(currentDecision.getRouteTarget())
                .needApproval(currentDecision.isNeedApproval())
                .build();
    }

    private List<SensitiveEntity> mergeEntities(List<SensitiveEntity> baseEntities,
            List<SensitiveEntity> agentEntities) {
        List<SensitiveEntity> merged = new ArrayList<>();
        if (baseEntities != null) {
            merged.addAll(baseEntities);
        }
        if (agentEntities != null) {
            for (SensitiveEntity entity : agentEntities) {
                boolean exists = merged.stream().anyMatch(existing -> existing.getType() == entity.getType()
                        && safeEquals(existing.getOriginalText(), entity.getOriginalText()));
                if (!exists) {
                    merged.add(entity);
                }
            }
        }
        return merged;
    }

    private Map<String, String> mergeMaskMappings(Map<String, String> baseMapping, Map<String, String> agentMapping) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (baseMapping != null) {
            merged.putAll(baseMapping);
        }
        if (agentMapping != null) {
            merged.putAll(agentMapping);
        }
        return merged;
    }

    private List<String> extractTypes(List<SensitiveEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream()
                .map(e -> e.getType() != null ? e.getType().name() : "UNKNOWN")
                .distinct()
                .collect(Collectors.toList());
    }

    private boolean safeEquals(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private String hash(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(payload.hashCode());
        }
    }
}
