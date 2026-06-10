package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.entity.SensitiveType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ConfidenceScorerTest {

    @Test
    void testCalculateConfidenceBasic() {
        ConfidenceScorer scorer = new ConfidenceScorer();
        String text = "联系号码：13800138000，备用：010-12345678";
        String matched = "13800138000";
        int start = text.indexOf(matched);
        int end = start + matched.length();
        double c = scorer.calculateConfidence(text, start, end, matched, SensitiveType.PHONE_NUMBER, false);
        assertTrue(c >= 0.0 && c <= 1.0);
    }

    @Test
    void testGetKeywordsForType() {
        ConfidenceScorer scorer = new ConfidenceScorer();
        assertNotNull(scorer.getKeywordsForType(SensitiveType.PHONE_NUMBER));
    }
}
