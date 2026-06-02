package com.hdu.apisensitivities.entity.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayResponse {
    private String code;
    private String message;
    private String requestId;
    private String traceId;
    private boolean success;
    private Object data;
    private GatewayRiskDecision risk;
    private AuditSummary audit;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditSummary {
        private String eventId;
        private GatewayRiskLevel inputRiskLevel;
        private GatewayRiskLevel outputRiskLevel;
    }
}
