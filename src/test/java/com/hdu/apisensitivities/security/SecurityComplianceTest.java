package com.hdu.apisensitivities.security;

import com.hdu.apisensitivities.entity.gateway.GatewayAuditEvent;
import com.hdu.apisensitivities.entity.gateway.GatewayDecisionAction;
import com.hdu.apisensitivities.entity.gateway.GatewayRiskLevel;
import com.hdu.apisensitivities.repository.GatewayAuditRepository;
import com.hdu.apisensitivities.utils.ContentCipher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全合规性校验测试。
 * <p>
 * 核心断言：
 * <ul>
 * <li><b>审计数据静态加密</b>：写入数据库的 original_content / processed_content 必须为密文
 * （enc:v1: 前缀），数据库中不得出现敏感明文——消除"审计存储明文"数据泄露风险；</li>
 * <li><b>读取解密一致</b>：通过仓储读回的事件内容与写入前明文完全一致；</li>
 * <li><b>历史数据兼容</b>：加密上线前的明文存量数据仍可正常读取；</li>
 * <li><b>加密算法健壮性</b>：AES-GCM 往返一致、篡改密文可被检测并拒绝解密。</li>
 * </ul>
 * </p>
 */
@SpringBootTest
class SecurityComplianceTest {

    @Autowired
    private GatewayAuditRepository auditRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void auditContent_encryptedAtRest_noPlaintextInDatabase() {
        String original = "手机号 13812345678，密钥 sk-proj-9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e";
        String processed = "手机号 [PHONE_NUMBER_1]，密钥 [API_KEY_1]";

        auditRepository.save(GatewayAuditEvent.builder()
                .eventId("evt-sec-enc-001")
                .timestamp(Instant.now())
                .userId("u10086")
                .department("安全部")
                .channel("BROWSER_PLUGIN")
                .requestType("PLUGIN_CHECK")
                .targetProvider("deepseek")
                .matchedSensitiveTypes(List.of("PHONE_NUMBER", "API_KEY"))
                .decisionAction(GatewayDecisionAction.DESENSITIZE_AND_ALLOW)
                .inputRiskLevel(GatewayRiskLevel.MEDIUM)
                .originalContent(original)
                .processedContent(processed)
                .requestHash("hash-001")
                .build());

        // 数据库原始列必须为密文，不得出现明文
        String rawOriginal = jdbcTemplate.queryForObject(
                "SELECT original_content FROM gateway_audit_event WHERE event_id = ?",
                String.class, "evt-sec-enc-001");
        String rawProcessed = jdbcTemplate.queryForObject(
                "SELECT processed_content FROM gateway_audit_event WHERE event_id = ?",
                String.class, "evt-sec-enc-001");

        assertTrue(rawOriginal.startsWith("enc:v1:"), "original_content 未被加密");
        assertFalse(rawOriginal.contains("13812345678"), "手机号明文残留在审计库");
        assertFalse(rawOriginal.contains("sk-proj-"), "API Key 明文残留在审计库");
        assertTrue(rawProcessed.startsWith("enc:v1:"), "processed_content 未被加密");
        assertFalse(rawProcessed.contains("PHONE_NUMBER_1"), "处理后内容也应以密文存储");

        // 读回后解密一致
        GatewayAuditEvent readBack = auditRepository.findById("evt-sec-enc-001").orElseThrow();
        assertEquals(original, readBack.getOriginalContent(), "解密后的原文与写入前不一致");
        assertEquals(processed, readBack.getProcessedContent(), "解密后的处理内容与写入前不一致");
    }

    @Test
    void legacyPlaintextRows_stillReadable_backwardCompatible() {
        // 模拟加密上线前写入的明文存量数据
        jdbcTemplate.update(
                "INSERT INTO gateway_audit_event (event_id, timestamp, channel, request_type, "
                        + "original_content, processed_content, request_hash) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "evt-sec-legacy-001", Timestamp.from(Instant.now()),
                "API_GATEWAY", "CHAT",
                "历史明文数据 13812345678", "历史脱敏结果", "hash-legacy");

        GatewayAuditEvent e = auditRepository.findById("evt-sec-legacy-001").orElseThrow();
        assertEquals("历史明文数据 13812345678", e.getOriginalContent(), "历史明文数据应原样可读");
    }

    @Test
    void contentCipher_roundTrip_andTamperDetection() {
        String plain = "绝密内容 6217001234567890 密码 P@ssw0rd123!";
        String cipher = ContentCipher.encrypt(plain);

        // 往返一致
        assertEquals(plain, ContentCipher.decrypt(cipher));
        // 密文不得包含明文
        assertFalse(cipher.contains("6217001234567890"), "密文中残留明文");
        assertFalse(cipher.contains("P@ssw0rd123!"), "密文中残留明文");

        // 篡改密文（改动末尾字符）→ 解密必须失败
        String tampered = cipher.substring(0, cipher.length() - 4) + "AAAA";
        assertThrows(IllegalStateException.class, () -> ContentCipher.decrypt(tampered),
                "篡改后的密文应被 GCM 认证检测并拒绝解密");

        // null / 空串语义
        assertNull(ContentCipher.encrypt(null));
        assertEquals("", ContentCipher.encrypt(""));
        assertNull(ContentCipher.decrypt(null));
        assertEquals("明文", ContentCipher.decrypt("明文"));
    }
}
