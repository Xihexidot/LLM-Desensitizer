package com.hdu.apisensitivities.service.gateway;

import com.hdu.apisensitivities.entity.LlmProvider;
import com.hdu.apisensitivities.entity.LlmRequest;
import com.hdu.apisensitivities.entity.LlmResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.gateway.GatewayAuditEvent;
import com.hdu.apisensitivities.entity.gateway.GatewayDecisionAction;
import com.hdu.apisensitivities.entity.gateway.GatewayFileTaskInfo;
import com.hdu.apisensitivities.entity.gateway.GatewayInvocationContext;
import com.hdu.apisensitivities.entity.gateway.GatewayRequest;
import com.hdu.apisensitivities.entity.gateway.GatewayResponse;
import com.hdu.apisensitivities.entity.gateway.GatewayRiskDecision;
import com.hdu.apisensitivities.entity.gateway.GatewayRiskLevel;
import com.hdu.apisensitivities.entity.gateway.GatewayTaskStatus;
import com.hdu.apisensitivities.repository.GatewayAuditRepository;
import com.hdu.apisensitivities.service.LlmProxyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Collections;
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
    private static final String DEFAULT_CHANNEL = "backend-api";
    private static final int MAX_FILE_TASKS = 500;
    private static final Duration FILE_TASK_TTL = Duration.ofHours(24);

    private final LlmProxyService llmProxyService;
    private final GatewayAuditRepository auditRepository;
    private final Map<String, GatewayFileTaskInfo> fileTasks = new ConcurrentHashMap<>();
    private final Map<String, Instant> fileTaskCreatedAt = new ConcurrentHashMap<>();

    @Override
    public GatewayResponse processChat(GatewayRequest request, GatewayInvocationContext invocationContext) {
        String content = request != null && request.getInput() != null ? request.getInput().getContent() : null;
        LlmProvider provider = resolveProvider(request);
        LlmResponse llmResponse = llmProxyService.processLlmRequest(buildLlmRequest(request, content, provider));

        List<String> matchedTypes = extractMatchedTypes(llmResponse.getInputSensitiveEntities());
        GatewayRiskDecision riskDecision = buildRiskDecision(matchedTypes, provider.name(), llmResponse.isSuccess());
        GatewayAuditEvent auditEvent = buildAuditEvent(invocationContext, "CHAT", provider.name(), matchedTypes,
                riskDecision, content, llmResponse.getDesensitizedResponse());
        auditRepository.save(auditEvent);

        Map<String, Object> data = new HashMap<>();
        data.put("provider", provider.name());
        data.put("actualRoute", riskDecision.getRouteTarget());
        data.put("originalPrompt", content);
        data.put("processedPrompt", resolveProcessedPrompt(content, llmResponse));
        data.put("responseText", llmResponse.isSuccess() ? llmResponse.getDesensitizedResponse() : null);
        data.put("processingTimeMs", llmResponse.getProcessingTimeMs());
        data.put("errorMessage", llmResponse.getErrorMessage());

        return buildGatewayResponse(invocationContext, llmResponse.isSuccess(),
                llmResponse.isSuccess() ? "success" : llmResponse.getErrorMessage(),
                data, riskDecision, auditEvent.getEventId());
    }

    @Override
    public GatewayResponse processStructured(GatewayRequest request, GatewayInvocationContext invocationContext) {
        Map<String, Object> structuredData = request != null && request.getInput() != null
                ? request.getInput().getStructuredData()
                : Collections.emptyMap();
        String serialized = structuredData != null ? structuredData.toString() : "";
        GatewayRequest normalizedRequest = request != null ? request : new GatewayRequest();
        if (normalizedRequest.getInput() == null) {
            normalizedRequest.setInput(GatewayRequest.InputPayload.builder().type("STRUCTURED").build());
        }
        normalizedRequest.getInput().setContent(serialized);
        GatewayResponse response = processChat(normalizedRequest, invocationContext);
        if (response.getData() instanceof Map<?, ?> dataMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mutable = (Map<String, Object>) dataMap;
            mutable.put("structuredInput", structuredData);
        }
        return response;
    }

    @Override
    public GatewayResponse createFileTask(String fileName, String sceneCode,
            GatewayInvocationContext invocationContext) {
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
                .matchedTypes(List.of())
                .matchedRules(List.of())
                .policyId(DEFAULT_POLICY_ID)
                .policyVersion(DEFAULT_POLICY_VERSION)
                .routeTarget(null)
                .needApproval(false)
                .build();
        GatewayAuditEvent auditEvent = buildAuditEvent(invocationContext, "FILE_TASK", null, List.of(), riskDecision,
                fileName, taskId);
        auditRepository.save(auditEvent);

        return buildGatewayResponse(invocationContext, true, "task accepted", taskInfo, riskDecision,
                auditEvent.getEventId());
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

    private LlmRequest buildLlmRequest(GatewayRequest request, String content, LlmProvider provider) {
        String sessionId = request != null && request.getRequestContext() != null
                ? request.getRequestContext().getSessionId()
                : null;
        return LlmRequest.builder()
                .provider(provider)
                .prompt(content)
                .sessionId(sessionId != null ? sessionId : "gateway-" + System.currentTimeMillis())
                .dataType("TEXT")
                .build();
    }

    private LlmProvider resolveProvider(GatewayRequest request) {
        String preferred = request != null && request.getProvider() != null ? request.getProvider().getPreferred()
                : null;
        if (preferred == null || preferred.isBlank()) {
            return LlmProvider.DEEPSEEK;
        }

        try {
            return LlmProvider.valueOf(preferred.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return LlmProvider.DEEPSEEK;
        }
    }

    private List<String> extractMatchedTypes(List<SensitiveEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream()
                .map(entity -> entity.getType() != null ? entity.getType().name() : "UNKNOWN")
                .distinct()
                .collect(Collectors.toList());
    }

    private GatewayRiskDecision buildRiskDecision(List<String> matchedTypes, String routeTarget, boolean success) {
        GatewayRiskLevel riskLevel = matchedTypes.isEmpty() ? GatewayRiskLevel.NONE : GatewayRiskLevel.MEDIUM;
        GatewayDecisionAction decisionAction = matchedTypes.isEmpty()
                ? GatewayDecisionAction.ALLOW
                : GatewayDecisionAction.DESENSITIZE_AND_ALLOW;
        if (!success) {
            riskLevel = GatewayRiskLevel.HIGH;
            decisionAction = GatewayDecisionAction.BLOCK;
        }

        return GatewayRiskDecision.builder()
                .riskLevel(riskLevel)
                .decisionAction(decisionAction)
                .matchedTypes(matchedTypes)
                .matchedRules(List.of())
                .policyId(DEFAULT_POLICY_ID)
                .policyVersion(DEFAULT_POLICY_VERSION)
                .routeTarget(routeTarget)
                .needApproval(false)
                .build();
    }

    private GatewayAuditEvent buildAuditEvent(GatewayInvocationContext invocationContext, String requestType,
            String targetProvider, List<String> matchedTypes, GatewayRiskDecision riskDecision, String requestPayload,
            String responsePayload) {
        return GatewayAuditEvent.builder()
                .eventId("evt-" + UUID.randomUUID())
                .timestamp(Instant.now())
                .tenantId(invocationContext.getTenantId())
                .appId(invocationContext.getAppId())
                .userId(invocationContext.getUserId())
                .department(invocationContext.getDepartment())
                .channel(invocationContext.getChannel())
                .requestType(requestType)
                .targetProvider(targetProvider)
                .targetModel(targetProvider)
                .sceneCode(invocationContext.getSceneCode())
                .matchedSensitiveTypes(matchedTypes)
                .decisionAction(riskDecision.getDecisionAction())
                .policyId(riskDecision.getPolicyId())
                .policyVersion(riskDecision.getPolicyVersion())
                .inputRiskLevel(riskDecision.getRiskLevel())
                .outputRiskLevel(riskDecision.getRiskLevel())
                .requestHash(hashPayload(requestPayload))
                .responseHash(hashPayload(responsePayload))
                .build();
    }

    private GatewayResponse buildGatewayResponse(GatewayInvocationContext invocationContext, boolean success,
            String message,
            Object data, GatewayRiskDecision riskDecision, String eventId) {
        return GatewayResponse.builder()
                .code(success ? "GW-0000" : "GW-3001")
                .message(message)
                .requestId(invocationContext.getRequestId())
                .traceId(invocationContext.getTraceId())
                .success(success)
                .data(data)
                .risk(riskDecision)
                .audit(GatewayResponse.AuditSummary.builder()
                        .eventId(eventId)
                        .inputRiskLevel(riskDecision.getRiskLevel())
                        .outputRiskLevel(riskDecision.getRiskLevel())
                        .build())
                .build();
    }

    private void cleanupExpiredFileTasks() {
        Instant expireBefore = Instant.now().minus(FILE_TASK_TTL);
        fileTaskCreatedAt.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().isBefore(expireBefore);
            if (expired) {
                fileTasks.remove(entry.getKey());
            }
            return expired;
        });
    }

    private void enforceFileTaskCapacity() {
        while (fileTasks.size() >= MAX_FILE_TASKS && !fileTaskCreatedAt.isEmpty()) {
            String oldestTaskId = fileTaskCreatedAt.entrySet().stream()
                    .min(Comparator.comparing(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldestTaskId == null) {
                break;
            }
            fileTasks.remove(oldestTaskId);
            fileTaskCreatedAt.remove(oldestTaskId);
        }
    }

    private String resolveProcessedPrompt(String originalContent, LlmResponse llmResponse) {
        if (llmResponse == null) {
            return originalContent;
        }
        String desensitizedPrompt = llmResponse.getDesensitizedResponse();
        return desensitizedPrompt != null ? desensitizedPrompt : originalContent;
    }

    private String hashPayload(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashBytes.length * 2);
            for (byte hashByte : hashBytes) {
                hex.append(String.format("%02x", hashByte));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }
}
