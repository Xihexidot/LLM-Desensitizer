package com.hdu.apisensitivities.controller;

import com.hdu.apisensitivities.entity.LlmProvider;
import com.hdu.apisensitivities.entity.gateway.GatewayAuditEvent;
import com.hdu.apisensitivities.entity.gateway.GatewayFileTaskInfo;
import com.hdu.apisensitivities.repository.GatewayAuditRepository;
import com.hdu.apisensitivities.service.gateway.EnterpriseGatewayApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/gateway/v1")
public class EnterpriseGatewayController {

    private final EnterpriseGatewayApplicationService gatewayService;
    private final GatewayAuditRepository auditRepository;

    public EnterpriseGatewayController(EnterpriseGatewayApplicationService gatewayService,
            GatewayAuditRepository auditRepository) {
        this.gatewayService = gatewayService;
        this.auditRepository = auditRepository;
    }

    // ========== OpenAI 兼容端点 ==========

    @PostMapping("/chat/completions")
    public ResponseEntity<Map<String, Object>> chatCompletions(
            @RequestBody Map<String, Object> body,
            @RequestHeader Map<String, String> headers) {

        String content = EnterpriseGatewayApplicationService.extractContentFromMessages(body.get("messages"));
        if (content.isBlank()) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", Map.of("message", "messages 中未找到有效 content"));
            return ResponseEntity.badRequest().body(err);
        }

        String model = body.get("model") instanceof String s ? s : null;
        LlmProvider provider = EnterpriseGatewayApplicationService.resolveProviderFromModel(model);

        Map<String, String> userHeaders = new HashMap<>();
        userHeaders.put("userId", headers.getOrDefault("x-user-id", headers.getOrDefault("X-User-Id", "")));
        userHeaders.put("department", headers.getOrDefault("x-department", headers.getOrDefault("X-Department", "")));
        userHeaders.put("channel", headers.getOrDefault("x-channel", headers.getOrDefault("X-Channel", "backend-api")));
        userHeaders.put("tenantId", headers.getOrDefault("x-tenant-id", headers.getOrDefault("X-Tenant-Id", "default")));
        userHeaders.put("appId", headers.getOrDefault("x-app-id", headers.getOrDefault("X-App-Id", "default")));

        Map<String, Object> result = gatewayService.processChatCompletions(content, provider, userHeaders);
        return ResponseEntity.ok(result);
    }

    // ========== 文件任务 ==========

    @PostMapping("/files/tasks")
    public ResponseEntity<GatewayFileTaskInfo> createFileTask(@RequestBody Map<String, Object> body) {
        String fileName = body.getOrDefault("fileName", "unknown").toString();
        String sceneCode = body.getOrDefault("sceneCode", "default").toString();
        String userId = body.getOrDefault("userId", "unknown").toString();
        String department = body.getOrDefault("department", "").toString();
        String tenantId = body.getOrDefault("tenantId", "default").toString();
        String appId = body.getOrDefault("appId", "default").toString();
        return ResponseEntity.ok(gatewayService.createFileTask(fileName, sceneCode, userId, department, tenantId, appId));
    }

    @GetMapping("/files/tasks/{taskId}")
    public ResponseEntity<GatewayFileTaskInfo> getFileTask(@PathVariable String taskId) {
        return gatewayService.getFileTask(taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ========== 审计查询 ==========

    @GetMapping("/audit/events")
    public ResponseEntity<List<GatewayAuditEvent>> queryAuditEvents(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String decisionAction) {
        return ResponseEntity.ok(gatewayService.queryAuditEvents(appId, userId, decisionAction));
    }

    @GetMapping("/audit/events/{eventId}")
    public ResponseEntity<Optional<GatewayAuditEvent>> getAuditEvent(@PathVariable String eventId) {
        return ResponseEntity.ok(auditRepository.findById(eventId));
    }

    @GetMapping("/audit/stats")
    public ResponseEntity<Map<String, Object>> getAuditStats() {
        return ResponseEntity.ok(auditRepository.getStats());
    }
}
