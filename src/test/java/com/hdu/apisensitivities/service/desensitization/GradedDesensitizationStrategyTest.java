package com.hdu.apisensitivities.service.desensitization;

import com.hdu.apisensitivities.config.DesensitizationRuleProperties;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveLevel;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.Desensitization.DesensitizeRequestContext;
import com.hdu.apisensitivities.service.Desensitization.GlobalSessionContextRepository;
import com.hdu.apisensitivities.service.Desensitization.GradedDesensitizationStrategy;
import com.hdu.apisensitivities.service.Desensitization.SensitiveDataClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分级分类脱敏策略单元测试：
 * HIGH→完全掩码、MEDIUM→部分脱敏、LOW→泛化处理、会话一致性、动态级别覆盖。
 */
class GradedDesensitizationStrategyTest {

    private GradedDesensitizationStrategy newStrategy() {
        return new GradedDesensitizationStrategy(new SensitiveDataClassifier(new DesensitizationRuleProperties()));
    }

    private SensitiveEntity entity(SensitiveType type, String text, int start, int end) {
        return SensitiveEntity.builder()
                .type(type)
                .originalText(text)
                .start(start)
                .end(end)
                .confidence(0.99)
                .build();
    }

    @Test
    void highLevelFullMask() {
        String text = "身份证号510104198303123639";
        GradedDesensitizationStrategy strategy = newStrategy();
        String result = strategy.desensitize(text, List.of(
                entity(SensitiveType.ID_CARD, "510104198303123639", 4, 22)));

        assertTrue(result.contains("[ID_CARD]"), "高敏应完全掩码，实际: " + result);
        assertFalse(result.contains("510104198303123639"));
    }

    @Test
    void mediumLevelPartialMask() {
        String text = "手机号13800138000";
        GradedDesensitizationStrategy strategy = newStrategy();
        String result = strategy.desensitize(text, List.of(
                entity(SensitiveType.PHONE_NUMBER, "13800138000", 3, 14)));

        assertTrue(result.contains("[138****8000]"), "中敏应部分脱敏，实际: " + result);
        assertFalse(result.contains("13800138000"));
    }

    @Test
    void lowLevelGeneralization() {
        String text = "住址浙江省杭州市西湖区";
        GradedDesensitizationStrategy strategy = newStrategy();
        String result = strategy.desensitize(text, List.of(
                entity(SensitiveType.ADDRESS, "浙江省杭州市西湖区", 2, 11)));

        assertTrue(result.contains("[浙江省地区]"), "低敏应泛化处理，实际: " + result);
        assertFalse(result.contains("浙江省杭州市西湖区"));
    }

    @Test
    void mixedLevelsInSingleText() {
        String text = "我的身份证号是510104198303123639，手机号13800138000，住址浙江省杭州市西湖区";
        GradedDesensitizationStrategy strategy = newStrategy();
        List<SensitiveEntity> entities = List.of(
                entity(SensitiveType.ID_CARD, "510104198303123639", 7, 25),
                entity(SensitiveType.PHONE_NUMBER, "13800138000", 29, 40),
                entity(SensitiveType.ADDRESS, "浙江省杭州市西湖区", 43, 52));
        String result = strategy.desensitize(text, entities);

        assertTrue(result.contains("[ID_CARD]"), result);
        assertTrue(result.contains("[138****8000]"), result);
        assertTrue(result.contains("[浙江省地区]"), result);
        assertFalse(result.contains("510104198303123639"));
        assertFalse(result.contains("13800138000"));
        assertFalse(result.contains("浙江省杭州市西湖区"));
    }

    @Test
    void entitiesOutOfOrderStillDesensitizedCorrectly() {
        // 实体列表按正序给出，策略需按 start 倒序替换，避免索引偏移
        String text = "张三 13800138000 510104198303123639";
        GradedDesensitizationStrategy strategy = newStrategy();
        List<SensitiveEntity> entities = List.of(
                entity(SensitiveType.PERSON, "张三", 0, 2),
                entity(SensitiveType.PHONE_NUMBER, "13800138000", 3, 14),
                entity(SensitiveType.ID_CARD, "510104198303123639", 15, 33));
        String result = strategy.desensitize(text, entities);

        assertTrue(result.contains("[张**]"), result);
        assertTrue(result.contains("[138****8000]"), result);
        assertTrue(result.contains("[ID_CARD]"), result);
        assertFalse(result.contains("张三"));
        assertFalse(result.contains("13800138000"));
        assertFalse(result.contains("510104198303123639"));
    }

    @Test
    void sessionConsistencySamePlaintextSamePlaceholder() {
        String text = "张三 和 张三 是朋友";
        GradedDesensitizationStrategy strategy = newStrategy();
        GlobalSessionContextRepository repo = new GlobalSessionContextRepository();
        ReflectionTestUtils.setField(strategy, "contextRepository", repo);
        DesensitizeRequestContext.setSessionId("session-test-1");
        try {
            List<SensitiveEntity> entities = List.of(
                    entity(SensitiveType.PERSON, "张三", 0, 2),
                    entity(SensitiveType.PERSON, "张三", 5, 7));
            String result = strategy.desensitize(text, entities);
            assertEquals("[张**_1] 和 [张**_1] 是朋友", result, result);
            long placeholderCount = result.chars().filter(c -> c == '[').count();
            assertEquals(2, placeholderCount, "同一明文应映射到同一占位符");
        } finally {
            DesensitizeRequestContext.clear();
        }
    }

    @Test
    void dynamicLevelOverrideChangesMaskStyle() {
        // 将 PHONE_NUMBER 动态升级为 HIGH → 由部分脱敏变为完全掩码
        DesensitizationRuleProperties props = new DesensitizationRuleProperties();
        props.getTypeLevels().put("PHONE_NUMBER", SensitiveLevel.HIGH);
        GradedDesensitizationStrategy strategy = new GradedDesensitizationStrategy(new SensitiveDataClassifier(props));

        String text = "手机号13800138000";
        String result = strategy.desensitize(text, List.of(
                entity(SensitiveType.PHONE_NUMBER, "13800138000", 3, 14)));

        assertTrue(result.contains("[PHONE]"), "级别覆盖后应完全掩码，实际: " + result);
    }

    @Test
    void structuredDataDesensitizedByLevel() {
        GradedDesensitizationStrategy strategy = newStrategy();
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("idCard", "510104198303123639");
        data.put("phone", "13800138000");

        SensitiveEntity idCard = entity(SensitiveType.ID_CARD, "510104198303123639", -1, -1);
        idCard.addFieldPath("idCard");
        SensitiveEntity phone = entity(SensitiveType.PHONE_NUMBER, "13800138000", -1, -1);
        phone.addFieldPath("phone");

        Map<String, Object> result = strategy.desensitizeStructuredData(data, List.of(idCard, phone));
        assertEquals("[ID_CARD]", result.get("idCard"));
        assertEquals("[138****8000]", result.get("phone"));
    }
}
