package com.hdu.apisensitivities.entity.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayInvocationContext {
    private String authorization;
    private String appId;
    private String requestId;
    private String tenantId;
    private String channel;
    private String traceId;
    private String userId;
    private String userRole;
    private String department;
    private String sceneCode;
    private String environment;
}
