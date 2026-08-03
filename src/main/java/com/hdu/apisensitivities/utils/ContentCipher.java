package com.hdu.apisensitivities.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 审计内容静态加密工具（AES-256-GCM）。
 * <p>
 * 用途：审计事件（网关/插件）中的原始内容与处理后内容在写入数据库前加密，
 * 消除"审计存储明文"这一数据泄露风险点，满足等保 2.0 / 数据安全法对敏感数据
 * 存储加密的合规要求。
 * </p>
 * <p>
 * 密文格式：{@code enc:v1:{base64(iv)}:{base64(ciphertext)}}，带 128 位 GCM 认证标签，
 * 保证机密性 + 完整性。密钥通过环境变量 {@code AUDIT_ENCRYPTION_KEY} 注入（32 字节），
 * 未配置时使用开发用默认密钥（仅限本地，生产必须注入）。
 * </p>
 */
public final class ContentCipher {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_LENGTH = 32; // AES-256

    private static final SecretKeySpec KEY = loadKey();

    /** 单例 SecureRandom：避免每次加密创建新实例（加密随机源复用，符合安全编码规范） */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ContentCipher() {
    }

    private static SecretKeySpec loadKey() {
        String raw = System.getenv("AUDIT_ENCRYPTION_KEY");
        if (raw == null || raw.isBlank()) {
            // 开发/测试默认密钥；生产环境必须通过 AUDIT_ENCRYPTION_KEY 注入
            raw = "ApiSensitivitiesDevKey-2026-32bytes!";
        }
        byte[] keyBytes = new byte[KEY_LENGTH];
        byte[] rawBytes = raw.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(rawBytes, 0, keyBytes, 0, Math.min(rawBytes.length, KEY_LENGTH));
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 加密明文。null/空串原样返回（保持列可空语义）。
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("审计内容加密失败", e);
        }
    }

    /**
     * 解密密文。
     * <ul>
     * <li>无 {@code enc:v1:} 前缀 → 视为加密上线前的历史明文，原样返回（向后兼容）；</li>
     * <li>带前缀但解密失败（密钥变更/数据损坏）→ 抛出异常，避免静默返回明文。</li>
     * </ul>
     */
    public static String decrypt(String payload) {
        if (payload == null || payload.isEmpty() || !payload.startsWith(PREFIX)) {
            return payload;
        }
        try {
            String body = payload.substring(PREFIX.length());
            String[] parts = body.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("密文格式非法");
            }
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("审计内容解密失败（密钥不匹配或数据损坏）", e);
        }
    }
}
