package com.hdu.apisensitivities.entity.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayRiskDecision {
    private GatewayRiskLevel riskLevel;
    private GatewayDecisionAction decisionAction;
    private List<String> matchedTypes;
    private List<String> matchedRules;
    private String policyId;
    private String policyVersion;
    private String routeTarget;
    private boolean needApproval;
}
