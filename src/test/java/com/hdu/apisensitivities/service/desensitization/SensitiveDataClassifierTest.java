package com.hdu.apisensitivities.service.desensitization;

import com.hdu.apisensitivities.config.DesensitizationRuleProperties;
import com.hdu.apisensitivities.entity.SensitiveLevel;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.Desensitization.SensitiveDataClassifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 敏感数据分级分类器单元测试：验证默认分级映射与动态规则覆盖能力。
 */
class SensitiveDataClassifierTest {

    @Test
    void defaultHighLevelTypes() {
        SensitiveDataClassifier classifier = new SensitiveDataClassifier(new DesensitizationRuleProperties());
        assertEquals(SensitiveLevel.HIGH, classifier.classify(SensitiveType.ID_CARD));
        assertEquals(SensitiveLevel.HIGH, classifier.classify(SensitiveType.BANK_CARD));
        assertEquals(SensitiveLevel.HIGH, classifier.classify(SensitiveType.CREDIT_CARD));
        assertEquals(SensitiveLevel.HIGH, classifier.classify(SensitiveType.PASSPORT));
        assertEquals(SensitiveLevel.HIGH, classifier.classify(SensitiveType.SOCIAL_SECURITY));
        assertEquals(SensitiveLevel.HIGH, classifier.classify(SensitiveType.PASSWORD));
        assertEquals(SensitiveLevel.HIGH, classifier.classify(SensitiveType.API_KEY));
    }

    @Test
    void defaultMediumLevelTypes() {
        SensitiveDataClassifier classifier = new SensitiveDataClassifier(new DesensitizationRuleProperties());
        assertEquals(SensitiveLevel.MEDIUM, classifier.classify(SensitiveType.PHONE_NUMBER));
        assertEquals(SensitiveLevel.MEDIUM, classifier.classify(SensitiveType.EMAIL));
        assertEquals(SensitiveLevel.MEDIUM, classifier.classify(SensitiveType.NAME));
        assertEquals(SensitiveLevel.MEDIUM, classifier.classify(SensitiveType.PERSON));
    }

    @Test
    void defaultLowLevelTypes() {
        SensitiveDataClassifier classifier = new SensitiveDataClassifier(new DesensitizationRuleProperties());
        assertEquals(SensitiveLevel.LOW, classifier.classify(SensitiveType.ADDRESS));
        assertEquals(SensitiveLevel.LOW, classifier.classify(SensitiveType.ORGANIZATION));
        assertEquals(SensitiveLevel.LOW, classifier.classify(SensitiveType.IP_ADDRESS));
        assertEquals(SensitiveLevel.LOW, classifier.classify(SensitiveType.LICENSE_PLATE));
        assertEquals(SensitiveLevel.LOW, classifier.classify(SensitiveType.CUSTOM));
    }

    @Test
    void nullTypeFallsBackToLow() {
        SensitiveDataClassifier classifier = new SensitiveDataClassifier(new DesensitizationRuleProperties());
        assertEquals(SensitiveLevel.LOW, classifier.classify(null));
    }

    @Test
    void dynamicRuleOverridesDefaultLevel() {
        DesensitizationRuleProperties props = new DesensitizationRuleProperties();
        props.getTypeLevels().put("PHONE_NUMBER", SensitiveLevel.HIGH);
        props.getTypeLevels().put("ADDRESS", SensitiveLevel.MEDIUM);
        SensitiveDataClassifier classifier = new SensitiveDataClassifier(props);
        assertEquals(SensitiveLevel.HIGH, classifier.classify(SensitiveType.PHONE_NUMBER));
        assertEquals(SensitiveLevel.MEDIUM, classifier.classify(SensitiveType.ADDRESS));
        // 未覆盖的类型保持默认
        assertEquals(SensitiveLevel.HIGH, classifier.classify(SensitiveType.ID_CARD));
    }

    @Test
    void levelStrategyNameMapping() {
        SensitiveDataClassifier classifier = new SensitiveDataClassifier(new DesensitizationRuleProperties());
        assertEquals("maskDesensitizationStrategy", classifier.strategyNameForLevel(SensitiveLevel.HIGH));
        assertEquals("partialDesensitizationStrategy", classifier.strategyNameForLevel(SensitiveLevel.MEDIUM));
        assertEquals("generalizationDesensitizationStrategy", classifier.strategyNameForLevel(SensitiveLevel.LOW));
    }
}
