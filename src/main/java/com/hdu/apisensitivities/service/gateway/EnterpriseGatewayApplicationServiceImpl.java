package com.hdu.apisensitivities.service.gateway;

import com.hdu.apisensitivities.entity.LlmProvider;
import com.hdu.apisensitivities.entity.LlmRequest;
import com.hdu.apisensitivities.entity.LlmResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.gateway.GatewayAuditEvent;
import com.hdu.apisensitivities.entity.gateway.GatewayDecisionAction;
import com.hdu.apisensitivities.entity.gateway.GatewayFileTaskInfo;
import com.hdu.apisensitivities.entity.gateway.GatewayRiskDecision;
import com.hdu.apisensitivities.entity.gateway.GatewayRiskLevel;
import com.hdu.apisensitivities.entity.gateway.GatewayTaskStatus;
import com.hdu.apisensitivities.repository.GatewayAuditRepository;
import com.hdu.apisensitivities.service.LlmProxyService;
import com.hdu.apisensitivities.controller.RiskPolicyController;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EnterpriseGatewayApplicationServiceImpl implements EnterpriseGatewayApplicationService {

    private static final String DEFAULT_POLICY_ID = "policy-default";
    private static final String DEFAULT_POLICY_VERSION = "skeleton-v1";
    private static final int MAX_FILE_TASKS = 500;
    private static final Duration FILE_TASK_TTL = Duration.ofHours(24);

    private final LlmProxyService llmProxyService;
    private final GatewayAuditRepository auditRepository;
    private final Map<String, GatewayFileTaskInfo> fileTasks = new ConcurrentHashMap<>();
    private final Map<String, Instant> fileTaskCreatedAt = new ConcurrentHashMap<>();

    // ========== OpenAI 兼容端点 ==========

    @Override
    public Map<String, Object> processChatCompletions(String content, LlmProvider provider,
            Map<String, String> headers) {
        String userId = headers.getOrDefault("userId", "unknown");
        String department = headers.getOrDefault("department", "");
        String channel = headers.getOrDefault("channel", "backend-api");
        String tenantId = headers.getOrDefault("tenantId", "default");
        String appId = headers.getOrDefault("appId", "default");

        LlmResponse llmResponse = llmProxyService.processLlmRequest(
                LlmRequest.builder()
                        .provider(provider)
                        .prompt(content)
                        .sessionId("gw-" + System.currentTimeMillis())
                        .dataType("TEXT")
                        .build());

        List<String> matchedTypes = extractMatchedTypes(llmResponse.getInputSensitiveEntities());
        GatewayRiskDecision riskDecision = buildRiskDecision(matchedTypes, provider.name(), llmResponse.isSuccess());

        GatewayAuditEvent auditEvent = GatewayAuditEvent.builder()
                .eventId("evt-" + UUID.randomUUID())
                .timestamp(Instant.now())
                .tenantId(tenantId)
                .appId(appId)
                .userId(userId)
                .department(department)
                .channel(channel)
                .requestType("CHAT")
                .targetProvider(normalizeProviderName(provider.name()))
                .targetModel(normalizeProviderName(provider.name()))
                .matchedSensitiveTypes(matchedTypes)
                .decisionAction(riskDecision.getDecisionAction())
                .inputRiskLevel(riskDecision.getRiskLevel())
                .outputRiskLevel(GatewayRiskLevel.NONE)
                .originalContent(content)
                .processedContent(llmResponse.getDesensitizedResponse())
                .requestHash(hashPayload(content))
                .responseHash(hashPayload(llmResponse.getDesensitizedResponse()))
                .build();
        auditRepository.save(auditEvent);

        String responseText = llmResponse.isSuccess()
                ? llmResponse.getDesensitizedResponse()
                : "Error: " + llmResponse.getErrorMessage();

        Map<String, Object> result = new HashMap<>();
        result.put("id", "chatcmpl-" + auditEvent.getEventId());
        result.put("object", "chat.completion");
        result.put("model", provider.name().toLowerCase());
        result.put("choices", List.of(Map.of(
                "index", 0,
                "message", Map.of("role", "assistant", "content", responseText),
                "finish_reason", llmResponse.isSuccess() ? "stop" : "error")));
        result.put("usage", Map.of(
                "prompt_tokens", content.length(),
                "completion_tokens", responseText.length(),
                "total_tokens", content.length() + responseText.length()));
        result.put("_audit", Map.of(
                "eventId", auditEvent.getEventId(),
                "riskLevel", riskDecision.getRiskLevel().name(),
                "decisionAction", riskDecision.getDecisionAction().name(),
                "matchedTypes", matchedTypes));
        return result;
    }

    // ========== 文件任务 ==========

    @Override
    public GatewayFileTaskInfo createFileTask(String fileName, String sceneCode, String userId, String department,
            String tenantId, String appId) {
        cleanupExpiredFileTasks();
        enforceFileTaskCapacity();

        String taskId = "task-" + UUID.randomUUID();
        GatewayFileTaskInfo taskInfo = GatewayFileTaskInfo.builder()
                .taskId(taskId)
                .status(GatewayTaskStatus.PENDING)
                .progress(0)
                .fileName(fileName)
                .sceneCode(sceneCode)
                .decisionAction(GatewayDecisionAction.ASYNC_REVIEW)
                .resultUrl("/gateway/v1/files/tasks/" + taskId)
                .build();
        fileTasks.put(taskId, taskInfo);
        fileTaskCreatedAt.put(taskId, Instant.now());

        GatewayRiskDecision riskDecision = GatewayRiskDecision.builder()
                .riskLevel(GatewayRiskLevel.LOW)
                .decisionAction(GatewayDecisionAction.ASYNC_REVIEW)
                .matchedTypes(List.of()).matchedRules(List.of())
                .policyId(DEFAULT_POLICY_ID).policyVersion(DEFAULT_POLICY_VERSION)
                .routeTarget(null).needApproval(false).build();
        GatewayAuditEvent auditEvent = GatewayAuditEvent.builder()
                .eventId("evt-" + UUID.randomUUID())
                .timestamp(Instant.now())
                .tenantId(tenantId).appId(appId).userId(userId).department(department)
                .channel("backend-api").requestType("FILE_TASK")
                .decisionAction(GatewayDecisionAction.ASYNC_REVIEW)
                .inputRiskLevel(GatewayRiskLevel.LOW).outputRiskLevel(GatewayRiskLevel.NONE)
                .originalContent(fileName).processedContent(taskId)
                .build();
        auditRepository.save(auditEvent);

        return taskInfo;
    }

    @Override
    public Optional<GatewayFileTaskInfo> getFileTask(String taskId) {
        cleanupExpiredFileTasks();
        return Optional.ofNullable(fileTasks.get(taskId));
    }

    @Override
    public List<GatewayAuditEvent> queryAuditEvents(String appId, String userId, String decisionAction) {
        return auditRepository.query(appId, userId, decisionAction, 200);
    }

    // ========== 内部方法 ==========

    private List<String> extractMatchedTypes(List<SensitiveEntity> entities) {
        if (entities == null || entities.isEmpty())
            return List.of();
        return entities.stream()
                .map(entity -> entity.getType() != null ? entity.getType().name() : "UNKNOWN")
                .distinct().collect(Collectors.toList());
    }

    private GatewayRiskDecision buildRiskDecision(List<String> matchedTypes, String routeTarget, boolean success) {
        if (!success) {
            return GatewayRiskDecision.builder()
                    .riskLevel(GatewayRiskLevel.HIGH).decisionAction(GatewayDecisionAction.BLOCK)
                    .matchedTypes(matchedTypes).matchedRules(List.of())
                    .policyId(DEFAULT_POLICY_ID).policyVersion(DEFAULT_POLICY_VERSION)
                    .routeTarget(routeTarget).needApproval(false).build();
        }

        int[] scoreResult = new int[2];
        RiskScorer.scoreWithLevel(matchedTypes, scoreResult);
        String riskLevelName = RiskScorer.levelName(scoreResult[1]);

        RiskPolicyController.PolicyConfig config = RiskPolicyController.getCurrentConfig();
        String defaultAction = config.global != null ? config.global.defaultAction : "DESENSITIZE_AND_ALLOW";
        int maxCount = config.global != null ? config.global.maxSensitiveCount : 5;

        if (matchedTypes != null && matchedTypes.size() > maxCount) {
            riskLevelName = "HIGH";
            defaultAction = "BLOCK";
        }

        GatewayRiskLevel riskLevel = switch (riskLevelName) {
            case "CRITICAL" -> GatewayRiskLevel.CRITICAL;
            case "HIGH" -> GatewayRiskLevel.HIGH;
            case "MEDIUM" -> GatewayRiskLevel.MEDIUM;
            case "LOW" -> GatewayRiskLevel.LOW;
            default -> GatewayRiskLevel.NONE;
        };

        GatewayDecisionAction decisionAction = switch (defaultAction) {
            case "BLOCK" -> GatewayDecisionAction.BLOCK;
            case "ALLOW" -> GatewayDecisionAction.ALLOW;
            default -> GatewayDecisionAction.DESENSITIZE_AND_ALLOW;
        };

        return GatewayRiskDecision.builder()
                .riskLevel(riskLevel).decisionAction(decisionAction)
                .matchedTypes(matchedTypes).matchedRules(List.of())
                .policyId(DEFAULT_POLICY_ID).policyVersion(DEFAULT_POLICY_VERSION)
                .routeTarget(routeTarget).needApproval(false).build();
    }

    private static String normalizeProviderName(String raw) {
        if (raw == null || raw.isBlank())
            return raw;
        return switch (raw) {
            case "DEEPSEEK" -> "DeepSeek";
            case "OPENAI", "AZURE_OPENAI" -> "OpenAI";
            case "DOUBAO" -> "豆包";
            case "CLAUDE" -> "Claude";
            case "QWEN" -> "通义千问";
            case "KIMI" -> "Kimi";
            case "HUNYUAN" -> "混元";
            case "OLLAMA" -> "Ollama (本地)";
            default -> raw;
        };
    }

    private String hashPayload(String payload) {
        if (payload == null)
            return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    private void cleanupExpiredFileTasks() {
        Instant expireBefore = Instant.now().minus(FILE_TASK_TTL);
        fileTaskCreatedAt.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(expireBefore)) {
                fileTasks.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    private void enforceFileTaskCapacity() {
        while (fileTasks.size() >= MAX_FILE_TASKS && !fileTaskCreatedAt.isEmpty()) {
            fileTaskCreatedAt.entrySet().stream()
                    .min(Comparator.comparing(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .ifPresent(key -> {
                        fileTasks.remove(key);
                        fileTaskCreatedAt.remove(key);
                    });
        }
    }
}
