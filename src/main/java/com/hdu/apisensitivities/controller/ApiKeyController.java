package com.hdu.apisensitivities.controller;

import com.hdu.apisensitivities.entity.ApiKey;
import com.hdu.apisensitivities.repository.ApiKeyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API Key 管理接口（开发/测试环境使用，生产环境应加入严格的管理员权限控制）
 */
@Slf4j
@RestController
@RequestMapping("/admin/api-keys")
public class ApiKeyController {
    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyController(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * 创建新的 API Key（明文仅返回一次）
     */
    @PostMapping
    public Map<String, Object> createApiKey(@RequestBody Map<String, String> request) {
        String name = request.getOrDefault("name", "未命名应用");
        String tenantId = request.getOrDefault("tenantId", "default");
        String userId = request.getOrDefault("userId", "");
        String department = request.getOrDefault("department", "");

        String plainKey = "sk-" + UUID.randomUUID().toString().replace("-", "");
        String id = UUID.randomUUID().toString();
        ApiKey saved = apiKeyRepository.saveApiKey(id, plainKey, name, tenantId, userId, department);

        log.info("创建 API Key: name={}, tenantId={}, userId={}", name, tenantId, userId);
        return Map.of(
                "id", saved.getId(),
                "key", plainKey, // 仅此次返回明文
                "name", saved.getName(),
                "tenantId", saved.getTenantId(),
                "userId", saved.getUserId(),
                "department", saved.getDepartment(),
                "createdAt", saved.getCreatedAt());
    }

    /**
     * 列出所有 API Key（不返回明文）
     */
    @GetMapping
    public List<ApiKey> listApiKeys() {
        return apiKeyRepository.listAll();
    }

    /**
     * 删除 API Key
     */
    @DeleteMapping("/{prefix}")
    public Map<String, String> deleteApiKey(@PathVariable String prefix) {
        apiKeyRepository.delete(prefix);
        log.info("删除 API Key，前缀: {}", prefix);
        return Map.of("message", "删除成功");
    }
}
