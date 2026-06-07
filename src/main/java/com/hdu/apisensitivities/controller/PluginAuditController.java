package com.hdu.apisensitivities.controller;

import com.hdu.apisensitivities.dto.ConfirmActionRequest;
import com.hdu.apisensitivities.dto.PluginCheckRequest;
import com.hdu.apisensitivities.dto.PluginCheckResponse;
import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.gateway.GatewayAuditEvent;
import com.hdu.apisensitivities.entity.gateway.GatewayDecisionAction;
import com.hdu.apisensitivities.entity.gateway.GatewayRiskLevel;
import com.hdu.apisensitivities.repository.GatewayAuditRepository;
import com.hdu.apisensitivities.service.DesensitizationManager;
import com.hdu.apisensitivities.service.gateway.RiskScorer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/plugin")
public class PluginAuditController {

    private final DesensitizationManager desensitizationManager;
    private final GatewayAuditRepository auditRepository;

    public PluginAuditController(DesensitizationManager desensitizationManager,
            GatewayAuditRepository auditRepository) {
        this.desensitizationManager = desensitizationManager;
        this.auditRepository = auditRepository;
    }

    @PostMapping("/audit-check")
    public ResponseEntity<PluginCheckResponse> auditCheck(@RequestBody PluginCheckRequest req) {
        DesensitizationRequest desensitizationRequest = new DesensitizationRequest();
        desensitizationRequest.setContent(req.getContent() != null ? req.getContent() : "");
        desensitizationRequest.setDataType(req.getDataType() != null ? req.getDataType() : "TEXT");
        desensitizationRequest.setStrictMode(req.isStrictMode());
        desensitizationRequest.setAutoScenarioDetection(req.isAutoScenarioDetection());

        DesensitizationResponse result = desensitizationManager.process(desensitizationRequest);

        String eventId = "evt-" + UUID.randomUUID();
        List<String> matchedTypes = extractTypes(result.getDetectedEntities());
        int[] scoreResult = new int[2];
        RiskScorer.scoreWithLevel(matchedTypes, scoreResult);
        int riskLevelInt = scoreResult[1];
        GatewayRiskLevel riskLevel;
        switch (riskLevelInt) {
            case 3:
                riskLevel = GatewayRiskLevel.HIGH;
                break;
            case 4:
                riskLevel = GatewayRiskLevel.CRITICAL;
                break;
            case 2:
                riskLevel = GatewayRiskLevel.MEDIUM;
                break;
            case 1:
                riskLevel = GatewayRiskLevel.LOW;
                break;
            default:
                riskLevel = GatewayRiskLevel.NONE;
                break;
        }

        GatewayAuditEvent event = GatewayAuditEvent.builder()
                .eventId(eventId)
                .timestamp(Instant.now())
                .userId(req.getUserId())
                .department(req.getDepartment())
                .channel("BROWSER_PLUGIN")
                .requestType("PLUGIN_CHECK")
                .targetProvider(req.getTargetProvider())
                .matchedSensitiveTypes(matchedTypes)
                .decisionAction(riskLevel == GatewayRiskLevel.NONE
                        ? GatewayDecisionAction.ALLOW
                        : GatewayDecisionAction.DESENSITIZE_AND_ALLOW)
                .inputRiskLevel(riskLevel)
                .outputRiskLevel(GatewayRiskLevel.NONE)
                .originalContent(req.getContent())
                .processedContent(result.getDesensitizedContent())
                .requestHash(hash(req.getContent()))
                .build();
        auditRepository.save(event);

        return ResponseEntity.ok(PluginCheckResponse.builder()
                .detectedEntities(result.getDetectedEntities())
                .desensitizedContent(result.getDesensitizedContent())
                .auditEventId(eventId)
                .build());
    }

    @PostMapping("/confirm-action")
    public ResponseEntity<Void> confirmAction(@RequestBody ConfirmActionRequest req) {
        if (req.getAuditEventId() != null && req.getUserAction() != null) {
            auditRepository.updateUserAction(req.getAuditEventId(), req.getUserAction());
        }
        return ResponseEntity.ok().build();
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
