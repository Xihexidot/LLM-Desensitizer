package com.hdu.apisensitivities.service.desensitization;

import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.service.DesensitizationManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏响应中"占位符 → 明文"反向映射（maskMapping）的集成测试。
 * <p>
 * 该映射是前端解码还原 AI 返回内容中脱敏标记的数据源：
 * 前端携带会话ID完成脱敏后，即可用返回的 maskMapping 将 AI 答复中的
 * [PHONE_1]、[MASKED_1] 等标记完整还原为原始业务数据。
 */
@SpringBootTest
class DesensitizationManagerMaskMappingTest {

        @Autowired
        private DesensitizationManager desensitizationManager;

        @Test
        void successResponseCarriesReverseMapping() {
                String content = "我的手机号是13800138000，身份证号510104198303123639";
                DesensitizationResponse response = desensitizationManager.process(DesensitizationRequest.builder()
                                .content(content)
                                .language("zh")
                                .dataType("TEXT")
                                .sessionId("mask-map-session-1")
                                .build());

                assertTrue(response.isSuccess());
                Map<String, String> mapping = response.getMaskMapping();
                assertNotNull(mapping, "脱敏成功后必须返回 maskMapping");
                assertFalse(mapping.isEmpty(), "脱敏成功后 maskMapping 不应为空");

                // 每个占位符对应的明文必须是原文中的真实片段
                for (Map.Entry<String, String> entry : mapping.entrySet()) {
                        assertTrue(entry.getKey().matches("\\[[^\\[\\]]+_\\d+\\]"),
                                        "占位符格式应为 [标识_序号]: " + entry.getKey());
                        assertTrue(content.contains(entry.getValue()),
                                        "映射明文应为原文片段: " + entry.getKey() + " -> " + entry.getValue());
                }
        }

        @Test
        void mappingStableAcrossSameSession() {
                String content = "张三的联系方式13800138000，备用电话13800138000";
                DesensitizationRequest request = DesensitizationRequest.builder()
                                .content(content)
                                .language("zh")
                                .dataType("TEXT")
                                .sessionId("same-session-mask")
                                .build();

                DesensitizationResponse first = desensitizationManager.process(request);
                DesensitizationResponse second = desensitizationManager.process(request);

                Map<String, String> firstMap = first.getMaskMapping();
                Map<String, String> secondMap = second.getMaskMapping();

                // 同一会话、同一明文 → 同一占位符 → 反向映射保持一致
                assertFalse(firstMap.isEmpty());
                for (Map.Entry<String, String> entry : firstMap.entrySet()) {
                        assertEquals(entry.getValue(), secondMap.get(entry.getKey()),
                                        "会话内同一明文应映射到同一占位符: " + entry.getKey());
                }
        }

        @Test
        void differentSessionsGenerateIndependentMapping() {
                // 会话 A 处理两个手机号，会话 B 只处理一个：各自独立从 1 编号，互不影响
                DesensitizationResponse a = desensitizationManager.process(DesensitizationRequest.builder()
                                .content("手机号13800138000和13900139000")
                                .language("zh").dataType("TEXT").sessionId("sess-a").build());
                DesensitizationResponse b = desensitizationManager.process(DesensitizationRequest.builder()
                                .content("手机号13800138000")
                                .language("zh").dataType("TEXT").sessionId("sess-b").build());

                Map<String, String> mapA = a.getMaskMapping();
        Map<String, String> mapB = b.getMaskMapping();
        assertFalse(mapA.isEmpty());
        assertFalse(mapB.isEmpty());
        // 会话隔离：会话 B 只有 1 条映射（编号从 1 重新开始），不继承会话 A 的计数
        assertEquals(2, mapA.size());
        assertEquals(1, mapB.size());
        // 不依赖具体占位符格式（Mask 策略为 [PHONE_1]，Graded MEDIUM 为 [138****8000_1]）
        assertTrue(mapB.containsValue("13800138000"), "会话B应能还原手机号明文");
    }
}
