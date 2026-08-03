package com.hdu.apisensitivities;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.Desensitization.MaskDesensitizationStrategy;
import com.hdu.apisensitivities.utils.PatternRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.*;

class LicensePlateDesensitizationUnitTests {

    @Test
    void regexMatchesNormalPlate() {
        String text = "车辆信息：浙A12345 已进入收费站";
        var pattern = PatternRegistry.getPattern(SensitiveType.LICENSE_PLATE);
        Matcher m = pattern.matcher(text);
        assertTrue(m.find());
        assertEquals("浙A12345", m.group());
    }

    @Test
    void regexMatchesNewEnergyPlateFrontDF() {
        String text = "新能源车牌：京AD12345 停靠区";
        var pattern = PatternRegistry.getPattern(SensitiveType.LICENSE_PLATE);
        Matcher m = pattern.matcher(text);
        assertTrue(m.find());
    }

    @Test
    void maskStrategyMasksLicensePlate() {
        String text = "车牌号为：沪B76543";
        SensitiveEntity entity = SensitiveEntity.builder()
                .type(SensitiveType.LICENSE_PLATE)
                .originalText("沪B76543")
                .start(text.indexOf("沪B76543"))
                .end(text.indexOf("沪B76543") + "沪B76543".length())
                .confidence(0.95)
                .build();
        String masked = new MaskDesensitizationStrategy().desensitize(text, List.of(entity));
        assertTrue(masked.contains("[PLATE]"));
    }
}
