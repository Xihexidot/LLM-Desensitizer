package com.hdu.apisensitivities.repository;

import com.hdu.apisensitivities.entity.ApiKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Key 存储仓库（当前使用内存存储，生产环境应使用数据库）
 */
@Slf4j
@Component
public class ApiKeyRepository {
    // 内存存储：前缀 -> 完整 ApiKey 对象
    private final Map<String, ApiKey> keyStore = new ConcurrentHashMap<>();

    // 初始化一个默认 API Key（方便测试）
    public ApiKeyRepository() {
        String defaultKey = "sk-test-abc123xyz";
        saveApiKey("default-test-key", defaultKey, "默认测试应用", "default", "test-user", "测试部门");
        log.info("已初始化测试 API Key，请妥善保管: {}", defaultKey.substring(0, Math.min(defaultKey.length(), 20)) + "...");
    }

    public ApiKey saveApiKey(String id, String plainKey, String name, String tenantId, String userId,
            String department) {
        String keyPrefix = extractPrefix(plainKey);
        String hashedKey = hashKey(plainKey);
        ApiKey apiKey = ApiKey.builder()
                .id(id)
                .keyPrefix(keyPrefix)
                .hashedKey(hashedKey)
                .name(name)
                .tenantId(tenantId)
                .userId(userId)
                .department(department)
                .createdAt(Instant.now())
                .enabled(true)
                .build();
        keyStore.put(keyPrefix, apiKey);
        return apiKey;
    }

    public Optional<ApiKey> findByPlainKey(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return Optional.empty();
        }
        String prefix = extractPrefix(plainKey);
        ApiKey stored = keyStore.get(prefix);
        if (stored == null)
            return Optional.empty();
        if (!stored.isEnabled())
            return Optional.empty();
        if (stored.getExpiresAt() != null && Instant.now().isAfter(stored.getExpiresAt())) {
            log.warn("API Key {} 已过期", stored.getName());
            return Optional.empty();
        }
        if (hashKey(plainKey).equals(stored.getHashedKey())) {
            return Optional.of(stored);
        }
        return Optional.empty();
    }

    public List<ApiKey> listAll() {
        return new ArrayList<>(keyStore.values());
    }

    public void delete(String prefix) {
        keyStore.remove(prefix);
    }

    // ========== 内部工具方法 ==========

    private String extractPrefix(String key) {
        // 提取前 20 字符作为索引前缀
        return key.substring(0, Math.min(key.length(), 20));
    }

    private String hashKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("API Key 哈希失败", e);
        }
    }
}
