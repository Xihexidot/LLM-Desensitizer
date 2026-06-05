package com.hdu.apisensitivities.controller;

import com.hdu.apisensitivities.entity.gateway.GatewayAuditEvent;
import com.hdu.apisensitivities.entity.gateway.GatewayFileTaskInfo;
import com.hdu.apisensitivities.entity.gateway.GatewayInvocationContext;
import com.hdu.apisensitivities.entity.gateway.GatewayRequest;
import com.hdu.apisensitivities.entity.gateway.GatewayResponse;
import com.hdu.apisensitivities.entity.gateway.GatewayRiskDecision;
import com.hdu.apisensitivities.entity.gateway.GatewayRiskLevel;
import com.hdu.apisensitivities.repository.GatewayAuditRepository;
import com.hdu.apisensitivities.service.gateway.EnterpriseGatewayApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/gateway/v1")
@RequiredArgsConstructor
public class EnterpriseGatewayController {

        private final EnterpriseGatewayApplicationService enterpriseGatewayApplicationService;
        private final GatewayAuditRepository auditRepository;

        @PostMapping("/chat")
        public ResponseEntity<GatewayResponse> chat(
                        @RequestBody GatewayRequest request,
                        @RequestHeader(value = "Authorization", required = false) String authorization,
                        @RequestHeader(value = "X-App-Id", required = false) String appId,
                        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                        @RequestHeader(value = "X-Channel", required = false) String channel,
                        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                        @RequestHeader(value = "X-User-Id", required = false) String userId,
                        @RequestHeader(value = "X-User-Role", required = false) String userRole,
                        @RequestHeader(value = "X-Department", required = false) String department,
                        @RequestHeader(value = "X-Scene-Code", required = false) String sceneCode,
                        @RequestHeader(value = "X-Environment", required = false) String environment) {
                GatewayInvocationContext context = buildInvocationContext(authorization, appId, requestId, tenantId,
                                channel,
                                traceId, userId, userRole, department, sceneCode, environment);
                return ResponseEntity.ok(enterpriseGatewayApplicationService.processChat(request, context));
        }

        @PostMapping("/structured")
        public ResponseEntity<GatewayResponse> structured(
                        @RequestBody GatewayRequest request,
                        @RequestHeader(value = "Authorization", required = false) String authorization,
                        @RequestHeader(value = "X-App-Id", required = false) String appId,
                        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                        @RequestHeader(value = "X-Channel", required = false) String channel,
                        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                        @RequestHeader(value = "X-User-Id", required = false) String userId,
                        @RequestHeader(value = "X-User-Role", required = false) String userRole,
                        @RequestHeader(value = "X-Department", required = false) String department,
                        @RequestHeader(value = "X-Scene-Code", required = false) String sceneCode,
                        @RequestHeader(value = "X-Environment", required = false) String environment) {
                GatewayInvocationContext context = buildInvocationContext(authorization, appId, requestId, tenantId,
                                channel,
                                traceId, userId, userRole, department, sceneCode, environment);
                return ResponseEntity.ok(enterpriseGatewayApplicationService.processStructured(request, context));
        }

        @PostMapping("/files/tasks")
        public ResponseEntity<GatewayResponse> createFileTask(
                        @RequestParam("fileName") String fileName,
                        @RequestParam(value = "sceneCode", required = false) String sceneCode,
                        @RequestHeader(value = "Authorization", required = false) String authorization,
                        @RequestHeader(value = "X-App-Id", required = false) String appId,
                        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                        @RequestHeader(value = "X-Channel", required = false) String channel,
                        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                        @RequestHeader(value = "X-User-Id", required = false) String userId,
                        @RequestHeader(value = "X-User-Role", required = false) String userRole,
                        @RequestHeader(value = "X-Department", required = false) String department,
                        @RequestHeader(value = "X-Environment", required = false) String environment) {
                GatewayInvocationContext context = buildInvocationContext(authorization, appId, requestId, tenantId,
                                channel,
                                traceId, userId, userRole, department, sceneCode, environment);
                return ResponseEntity
                                .ok(enterpriseGatewayApplicationService.createFileTask(fileName, sceneCode, context));
        }

        @GetMapping("/files/tasks/{taskId}")
        public ResponseEntity<GatewayResponse> getFileTask(
                        @PathVariable String taskId,
                        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                        @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
                return enterpriseGatewayApplicationService.getFileTask(taskId)
                                .map(task -> ResponseEntity.ok(buildFileTaskResponse(task, requestId, traceId)))
                                .orElseGet(() -> ResponseEntity.ok(buildNotFoundResponse(requestId, traceId, taskId)));
        }

        @GetMapping("/audit/events")
        public ResponseEntity<GatewayResponse> getAuditEvents(
                        @RequestParam(value = "appId", required = false) String appId,
                        @RequestParam(value = "userId", required = false) String userId,
                        @RequestParam(value = "decisionAction", required = false) String decisionAction,
                        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                        @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
                List<GatewayAuditEvent> events = enterpriseGatewayApplicationService.queryAuditEvents(appId, userId,
                                decisionAction);
                Map<String, Object> data = new HashMap<>();
                data.put("items", events);
                data.put("total", events.size());

                return ResponseEntity.ok(GatewayResponse.builder()
                                .code("GW-0000")
                                .message("success")
                                .requestId(requestId)
                                .traceId(traceId)
                                .success(true)
                                .data(data)
                                .risk(GatewayRiskDecision.builder()
                                                .riskLevel(GatewayRiskLevel.NONE)
                                                .decisionAction(null)
                                                .build())
                                .build());
        }

        @GetMapping("/audit/stats")
        public ResponseEntity<Map<String, Object>> getAuditStats() {
                return ResponseEntity.ok(auditRepository.getStats());
        }

        private GatewayInvocationContext buildInvocationContext(String authorization, String appId, String requestId,
                        String tenantId, String channel, String traceId, String userId, String userRole,
                        String department,
                        String sceneCode, String environment) {
                return GatewayInvocationContext.builder()
                                .authorization(authorization)
                                .appId(appId)
                                .requestId(requestId)
                                .tenantId(tenantId)
                                .channel(channel != null ? channel : "backend-api")
                                .traceId(traceId)
                                .userId(userId)
                                .userRole(userRole)
                                .department(department)
                                .sceneCode(sceneCode)
                                .environment(environment != null ? environment : "prod")
                                .build();
        }

        private GatewayResponse buildFileTaskResponse(GatewayFileTaskInfo task, String requestId, String traceId) {
                return GatewayResponse.builder()
                                .code("GW-0000")
                                .message("success")
                                .requestId(requestId)
                                .traceId(traceId)
                                .success(true)
                                .data(task)
                                .risk(GatewayRiskDecision.builder()
                                                .riskLevel(GatewayRiskLevel.LOW)
                                                .decisionAction(task.getDecisionAction())
                                                .build())
                                .build();
        }

        private GatewayResponse buildNotFoundResponse(String requestId, String traceId, String taskId) {
                return GatewayResponse.builder()
                                .code("GW-2001")
                                .message("task not found: " + taskId)
                                .requestId(requestId)
                                .traceId(traceId)
                                .success(false)
                                .risk(GatewayRiskDecision.builder()
                                                .riskLevel(GatewayRiskLevel.NONE)
                                                .build())
                                .build();
        }
}
