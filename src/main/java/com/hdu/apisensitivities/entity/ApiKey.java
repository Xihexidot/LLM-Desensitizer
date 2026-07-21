package com.hdu.apisensitivities.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * API Key 实体类，用于 API 调用方身份认证和权限控制
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKey {
    private String id;
    private String keyPrefix; // 用于快速查找的前缀（如 sk-xxxx）
    private String hashedKey; // 加密存储的密钥（不存明文）
    private String name; // 应用名称
    private String tenantId; // 租户/组织 ID
    private String userId; // 绑定的用户 ID（可选）
    private String department; // 绑定的部门（可选）
    private Instant createdAt;
    private Instant expiresAt; // 过期时间（null 表示永久）
    private boolean enabled;
}
