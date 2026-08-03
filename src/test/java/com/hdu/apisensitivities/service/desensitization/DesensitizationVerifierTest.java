package com.hdu.apisensitivities.service.desensitization;

import com.hdu.apisensitivities.config.DesensitizationRuleProperties;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.Desensitization.DesensitizationVerifier;
import com.hdu.apisensitivities.service.Desensitization.DesensitizationVerifier.VerificationResult;
import com.hdu.apisensitivities.service.SensitiveDetection.TextSensitiveDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 脱敏结果双重校验器单元测试：明文残留扫描、整体二次扫描、覆盖率阈值、人工复核队列。
 */
class DesensitizationVerifierTest {

        private DesensitizationRuleProperties props;
        private TextSensitiveDetectionService detectionService;
        private DesensitizationVerifier verifier;

        @BeforeEach
        void setUp() {
                props = new DesensitizationRuleProperties();
                detectionService = mock(TextSensitiveDetectionService.class);
                verifier = new DesensitizationVerifier(props, detectionService);
        }

        private SensitiveEntity entity(SensitiveType type, String text) {
                return SensitiveEntity.builder().type(type).originalText(text).start(0).end(text.length())
                                .confidence(0.99)
                                .build();
        }

        @Test
        void allEntitiesMaskedPassesVerification() {
                when(detectionService.detectSensitiveInfo(any(), any(), any(), any())).thenReturn(List.of());

                VerificationResult result = verifier.verify(
                                "身份证号510104198303123639",
                                "身份证号[ID_CARD_1]",
                                List.of(entity(SensitiveType.ID_CARD, "510104198303123639")),
                                "zh");

                assertTrue(result.isEnabled());
                assertTrue(result.isPassed());
                assertEquals(1.0, result.getCoverage(), 0.0001);
                assertTrue(result.getResidualTexts().isEmpty());
                assertEquals(0, verifier.pendingReviewCount());
        }

        @Test
        void residualPlaintextTriggersManualReview() {
                when(detectionService.detectSensitiveInfo(any(), any(), any(), any())).thenReturn(List.of());

                // 脱敏后原文仍残留 → 覆盖率 0
                VerificationResult result = verifier.verify(
                                "手机号13800138000",
                                "手机号13800138000",
                                List.of(entity(SensitiveType.PHONE_NUMBER, "13800138000")),
                                "zh");

                assertTrue(result.needsManualReview());
                assertEquals(0.0, result.getCoverage(), 0.0001);
                assertTrue(result.getResidualTexts().contains("13800138000"));
                assertTrue(result.isEnqueued());
                assertNotNull(result.getReviewId());
                assertEquals(1, verifier.pendingReviewCount());
                assertSame(DesensitizationVerifier.ReviewStatus.PENDING,
                                verifier.getPendingReviews().get(0).getStatus());
        }

        @Test
        void coverageBelowThresholdTriggersManualReview() {
                when(detectionService.detectSensitiveInfo(any(), any(), any(), any())).thenReturn(List.of());

                // 2 个实体，仅 1 个被脱敏 → 覆盖率 0.5 < 0.9 阈值
                VerificationResult result = verifier.verify(
                                "身份证号510104198303123639 手机号13800138000",
                                "身份证号[ID_CARD_1] 手机号13800138000",
                                List.of(
                                                entity(SensitiveType.ID_CARD, "510104198303123639"),
                                                entity(SensitiveType.PHONE_NUMBER, "13800138000")),
                                "zh");

                assertEquals(0.5, result.getCoverage(), 0.0001);
                assertTrue(result.needsManualReview());
        }

        @Test
        void secondScanFindsResidualPattern() {
                // 首轮未识别到的敏感模式在脱敏后仍存在 → 二次扫描兜底发现
                when(detectionService.detectSensitiveInfo(any(), any(), any(), any())).thenReturn(List.of(
                                SensitiveEntity.builder().type(SensitiveType.PHONE_NUMBER)
                                                .originalText("13912345678").start(0).end(11).confidence(0.99)
                                                .build()));

                VerificationResult result = verifier.verify(
                                "转发13912345678给我",
                                "转发13912345678给我", // 脱敏后仍残留手机号
                                List.of(), // 首轮未识别到任何实体
                                "zh");

                assertTrue(result.needsManualReview());
                assertTrue(result.getReDetectedTexts().contains("13912345678"));
                // 无首轮实体时覆盖率计为 1.0，但二次扫描残留依然触发复核
                assertEquals(1.0, result.getCoverage(), 0.0001);
        }

        @Test
        void verifyDisabledReturnsDisabledResult() {
                props.setVerifyEnabled(false);
                VerificationResult result = verifier.verify("任何文本", "任何文本", List.of(), "zh");
                assertFalse(result.isEnabled());
                assertTrue(result.isPassed());
                assertEquals(0, verifier.pendingReviewCount());
        }

        @Test
        void manualReviewDisabledSkipsEnqueue() {
                props.setManualReviewEnabled(false);
                when(detectionService.detectSensitiveInfo(any(), any(), any(), any())).thenReturn(List.of());

                VerificationResult result = verifier.verify(
                                "手机号13800138000",
                                "手机号13800138000",
                                List.of(entity(SensitiveType.PHONE_NUMBER, "13800138000")),
                                "zh");

                assertTrue(result.needsManualReview());
                assertFalse(result.isEnqueued());
                assertNull(result.getReviewId());
        }

        @Test
        void reScanFailureFallsBackToResidualCheck() {
                when(detectionService.detectSensitiveInfo(any(), any(), any(), any()))
                                .thenThrow(new RuntimeException("NLP服务不可用"));

                VerificationResult result = verifier.verify(
                                "手机号13800138000",
                                "手机号[138****8000]",
                                List.of(entity(SensitiveType.PHONE_NUMBER, "13800138000")),
                                "zh");

                // 二次扫描降级，但明文残留校验正常
                assertTrue(result.isPassed());
                assertEquals(1.0, result.getCoverage(), 0.0001);
        }
}
