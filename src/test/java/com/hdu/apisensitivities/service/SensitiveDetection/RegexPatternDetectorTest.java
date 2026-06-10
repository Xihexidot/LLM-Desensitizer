package com.hdu.apisensitivities.service.SensitiveDetection;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class RegexPatternDetectorTest {

    @Test
    void testDetectCustomPattern() {
        RegexPatternDetector detector = new RegexPatternDetector();
        String text = "用户身份证号：320981199001011234";
        Pattern p = Pattern.compile("\\\b\\d{17}[0-9Xx]\\\b");
        List<?> entities = detector.detectCustomPattern(text, "testId", p);
        assertNotNull(entities);
    }
}
