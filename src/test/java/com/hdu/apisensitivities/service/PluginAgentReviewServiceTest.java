package com.hdu.apisensitivities.service;

import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.SensitiveDetection.NlpScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginAgentReviewServiceTest {

        @Mock
        private NlpScanner nlpScanner;

        private PluginAgentReviewService reviewService;

        @BeforeEach
        void setUp() {
                reviewService = new PluginAgentReviewService(nlpScanner);
                ReflectionTestUtils.setField(reviewService, "enhancedReviewEnabled", true);
                ReflectionTestUtils.setField(reviewService, "failOpen", true);
        }

        @Test
        void review_shouldDegradeGracefully_whenAgentUnavailable() {
                when(nlpScanner.getStatus()).thenReturn(new NlpScanner.AgentStatus(
                                true, false, "OLLAMA_LOCAL", "http://127.0.0.1:11434/api/generate", "deepseek-r1:1.5b",
                                "Connection refused"));

                PluginAgentReviewService.AgentReviewResult result = reviewService.review(
                                "客户张三在天枢项目组",
                                baseResponse("客户张三在天枢项目组"));

                assertTrue(result.enabled());
                assertFalse(result.available());
                assertFalse(result.used());
                assertEquals("RULE_NER", result.mode());
                assertTrue(result.message().contains("自动降级"));
        }

        @Test
        void review_shouldAppendSemanticMask_whenAgentFindsNewEntities() {
                when(nlpScanner.getStatus()).thenReturn(new NlpScanner.AgentStatus(
                                true, true, "OLLAMA_LOCAL", "http://127.0.0.1:11434/api/generate", "deepseek-r1:1.5b",
                                "OK"));
                when(nlpScanner.extractEntities("客户张三正在推进天枢项目")).thenReturn(List.of("张三", "天枢项目"));
                when(nlpScanner.checkSafety("客户[ENTITY_2]正在推进[ENTITY_1]")).thenReturn(false);

                PluginAgentReviewService.AgentReviewResult result = reviewService.review(
                                "客户张三正在推进天枢项目",
                                baseResponse("客户张三正在推进天枢项目"));

                assertTrue(result.available());
                assertTrue(result.used());
                assertEquals("客户[ENTITY_2]正在推进[ENTITY_1]", result.desensitizedContent());
                assertEquals(Map.of("[ENTITY_1]", "天枢项目", "[ENTITY_2]", "张三"), result.maskMapping());
                assertEquals(2, result.agentEntities().size());
        }

        @Test
        void review_shouldSkipAlreadyMaskedEntity_whenBaseMaskContainsValue() {
                when(nlpScanner.getStatus()).thenReturn(new NlpScanner.AgentStatus(
                                true, true, "OLLAMA_LOCAL", "http://127.0.0.1:11434/api/generate", "deepseek-r1:1.5b",
                                "OK"));
                when(nlpScanner.extractEntities("客户张三正在推进天枢项目")).thenReturn(List.of("张三", "天枢项目"));
                when(nlpScanner.checkSafety("客户[NAME_1]正在推进[ENTITY_1]")).thenReturn(false);

                PluginAgentReviewService.AgentReviewResult result = reviewService.review(
                                "客户张三正在推进天枢项目",
                                DesensitizationResponse.builder()
                                                .originalContent("客户张三正在推进天枢项目")
                                                .desensitizedContent("客户[NAME_1]正在推进天枢项目")
                                                .detectedEntities(List.of(SensitiveEntity.builder()
                                                                .type(SensitiveType.NAME)
                                                                .originalText("张三")
                                                                .content("张三")
                                                                .start(2)
                                                                .end(4)
                                                                .confidence(0.95)
                                                                .build()))
                                                .maskMapping(Map.of("[NAME_1]", "张三"))
                                                .success(true)
                                                .build());

                assertTrue(result.used());
                assertEquals(List.of("天枢项目"), result.semanticEntities());
                assertEquals("客户[NAME_1]正在推进[ENTITY_1]", result.desensitizedContent());
                assertEquals(Map.of("[ENTITY_1]", "天枢项目"), result.maskMapping());
        }

        private DesensitizationResponse baseResponse(String content) {
                return DesensitizationResponse.builder()
                                .originalContent(content)
                                .desensitizedContent(content)
                                .detectedEntities(List.of())
                                .maskMapping(Map.of())
                                .success(true)
                                .build();
        }
}
