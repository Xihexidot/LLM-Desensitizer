package com.hdu.apisensitivities.controller;

import com.hdu.apisensitivities.dto.PluginCheckRequest;
import com.hdu.apisensitivities.dto.PluginCheckResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件审计接口 audit-check 响应中 maskMapping（占位符 → 明文）透传的集成测试。
 * <p>
 * 插件"一键复原"依赖该字段将 AI 返回内容中的脱敏标记还原为原始业务数据，
 * 必须确保 /plugin/audit-check 响应与内部脱敏结果携带完全一致的反向映射。
 */
@SpringBootTest
class PluginAuditControllerMaskMappingTest {

        @Autowired
        private PluginAuditController pluginAuditController;

        @Test
        void auditCheckResponseCarriesMaskMapping() {
                String content = "我的手机号是13800138000，身份证号510104198303123639";
                PluginCheckRequest request = PluginCheckRequest.builder()
                                .content(content)
                                .dataType("TEXT")
                                .userId("测试员工")
                                .department("安全测试部")
                                .targetProvider("DeepSeek")
                                .build();

                PluginCheckResponse response = pluginAuditController.auditCheck(request).getBody();
                assertNotNull(response, "audit-check 必须返回响应体");
                assertNotNull(response.getDetectionMode(), "响应中应返回检测模式");

                // 脱敏内容必须包含占位符标记
                assertTrue(response.getDesensitizedContent().contains("["),
                                "脱敏内容应包含脱敏标记");

                // 响应必须透传反向映射，且映射明文均为原文真实片段
                Map<String, String> maskMapping = response.getMaskMapping();
                assertNotNull(maskMapping, "audit-check 响应必须携带 maskMapping");
                assertFalse(maskMapping.isEmpty(), "含敏感内容时 maskMapping 不应为空");
                for (Map.Entry<String, String> entry : maskMapping.entrySet()) {
                        assertTrue(entry.getKey().matches("\\[[^\\[\\]]+_\\d+\\]"),
                                        "占位符格式应为 [标识_序号]: " + entry.getKey());
                        assertTrue(content.contains(entry.getValue()),
                                        "映射明文应为原文片段: " + entry.getKey() + " -> " + entry.getValue());
                }
        }

        @Test
        void auditCheckMappingConsistentWithDesensitizedContent() {
                // 无敏感信息的文本
                PluginCheckRequest plain = PluginCheckRequest.builder()
                                .content("今天天气不错，帮我写一段关于杭州的介绍。")
                                .dataType("TEXT")
                                .build();
                PluginCheckResponse plainResp = pluginAuditController.auditCheck(plain).getBody();
                assertNotNull(plainResp);
                assertNotNull(plainResp.getDetectionMode(), "无敏感内容时也应返回检测模式");
                assertNotNull(plainResp.getMaskMapping(), "无敏感内容时也应返回映射（非 null）");
                // 脱敏内容不含任何占位符 → 映射必须为空（映射与脱敏结果严格联动）
                if (!plainResp.getDesensitizedContent().matches(".*\\[[^\\[\\]]+_\\d+\\].*")) {
                        assertTrue(plainResp.getMaskMapping().isEmpty(),
                                        "脱敏内容无占位符时映射应为空");
                }

                // 含敏感信息的文本：脱敏内容含占位符 → 映射非空，且明文均为原文片段
                String sensitive = "我的手机号是13800138000，身份证号510104198303123639";
                PluginCheckResponse sensResp = pluginAuditController.auditCheck(
                                PluginCheckRequest.builder().content(sensitive).dataType("TEXT").build()).getBody();
                assertNotNull(sensResp);
                assertTrue(sensResp.getDesensitizedContent().matches(".*\\[[^\\[\\]]+_\\d+\\].*"),
                                "含敏感内容时脱敏内容应含占位符");
                assertFalse(sensResp.getMaskMapping().isEmpty(), "含敏感内容时映射不应为空");
                for (Map.Entry<String, String> entry : sensResp.getMaskMapping().entrySet()) {
                        assertTrue(sensitive.contains(entry.getValue()),
                                        "映射明文应为原文片段: " + entry.getValue());
                }
        }
}
