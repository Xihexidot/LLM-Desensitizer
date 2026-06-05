package com.hdu.apisensitivities.entity.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayAuditEvent {
    private String eventId;
    private Instant timestamp;
    private String tenantId;
    private String appId;
    private String userId;
    private String department;
    private String channel;
    private String requestType;
    private String targetProvider;
    private String targetModel;
    private String sceneCode;
    private List<String> matchedSensitiveTypes;
    private GatewayDecisionAction decisionAction;
    private String policyId;
    private String policyVersion;
    private GatewayRiskLevel inputRiskLevel;
    private GatewayRiskLevel outputRiskLevel;
    private String userAction;
    private String originalContent;
    private String processedContent;
    private String requestHash;
    private String responseHash;
}
