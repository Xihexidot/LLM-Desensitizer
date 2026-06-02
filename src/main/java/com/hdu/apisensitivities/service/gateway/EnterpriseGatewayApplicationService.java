package com.hdu.apisensitivities.service.gateway;

import com.hdu.apisensitivities.entity.gateway.GatewayAuditEvent;
import com.hdu.apisensitivities.entity.gateway.GatewayFileTaskInfo;
import com.hdu.apisensitivities.entity.gateway.GatewayInvocationContext;
import com.hdu.apisensitivities.entity.gateway.GatewayRequest;
import com.hdu.apisensitivities.entity.gateway.GatewayResponse;

import java.util.List;
import java.util.Optional;

public interface EnterpriseGatewayApplicationService {
    GatewayResponse processChat(GatewayRequest request, GatewayInvocationContext invocationContext);

    GatewayResponse processStructured(GatewayRequest request, GatewayInvocationContext invocationContext);

    GatewayResponse createFileTask(String fileName, String sceneCode, GatewayInvocationContext invocationContext);

    Optional<GatewayFileTaskInfo> getFileTask(String taskId);

    List<GatewayAuditEvent> queryAuditEvents(String appId, String userId, String decisionAction);
}
