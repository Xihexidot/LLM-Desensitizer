package com.hdu.apisensitivities.service;

import com.hdu.apisensitivities.config.LlmConfig;
import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.entity.LlmProvider;
import com.hdu.apisensitivities.entity.LlmRequest;
import com.hdu.apisensitivities.entity.LlmResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.Desensitization.SemanticPlaceholderStrategy;
import com.hdu.apisensitivities.service.LlmClient.LlmClient;
import com.hdu.apisensitivities.service.SensitiveDetection.NlpScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 运行链路集成测试。
 * <p>
 * 使用「真实 SemanticPlaceholderStrategy + mock 外部依赖」的方式，
 * 端到端验证 LlmProxyService 的完整 Agent 链路：
 * 基础脱敏 → Agent 语义实体提取 → 占位符脱敏 → 自反思审计 → LLM 调用 → 响应还原。
 * </p>
 * <p>
 * 重点覆盖：
 * <ul>
 * <li>发送给云端 LLM 的 prompt 不得包含原始敏感数据（泄露验证）</li>
 * <li>Agent 降级（空/null 实体）时回退基础脱敏内容</li>
 * <li>LLM 调用异常中断后，下一请求不得复用上一请求的映射（跨请求泄露）</li>
 * <li>多用户并发场景下各请求独立还原</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class LlmProxyServiceAgentFlowTest {

        private static final String RAW_PROMPT = "张三的电话是13800138000，请分析他的联系人";
        private static final String BASE_DESENSITIZED = "张三的电话是[PHONE_NUMBER]，请分析他的联系人";

        @Mock
        private NlpScanner nlpScanner;

        @Mock
        private DesensitizationManager desensitizationManager;

        @Mock
        private LlmConfigService configService;

        @Mock
        private LlmClient deepSeekClient;

        private final SemanticPlaceholderStrategy semanticStrategy = new SemanticPlaceholderStrategy();

        private LlmProxyService llmProxyService;

        private LlmConfig config;

        @BeforeEach
        void setUp() {
                // LlmProxyService 构造函数会调用 getSupportedProvider() 建立 provider→client 映射，
                // 必须在构造前 stub，否则映射 key 为 null 导致后续 NPE。
                when(deepSeekClient.getSupportedProvider()).thenReturn(LlmProvider.DEEPSEEK);

                config = LlmConfig.builder()
                                .provider(LlmProvider.DEEPSEEK)
                                .apiUrl("http://localhost:9999/v1")
                                .apiKey("test-key")
                                .model("test-model")
                                .temperature(0.7)
                                .maxTokens(100)
                                .build();
                llmProxyService = new LlmProxyService(
                                nlpScanner, semanticStrategy, desensitizationManager, configService,
                                List.of(deepSeekClient));
        }

        // ==================== 正常业务流 ====================

        /**
         * 正常业务流：基础脱敏 → Agent 提取"张三" → [ENTITY_1] 占位符 → LLM 调用 →
         * 响应中 [ENTITY_1] 被还原为"张三"。
         */
        @Test
        void agentFlow_shouldMaskAndRestore_fullPipeline() {
                stubCommonDeps();
                when(nlpScanner.extractEntities(anyString())).thenReturn(List.of("张三"));
                when(nlpScanner.checkSafety(anyString())).thenReturn(false);
                when(deepSeekClient.sendRequest(anyString(), any(), any()))
                                .thenReturn("根据[ENTITY_1]的电话号码分析，建议联系[ENTITY_1]本人");

                LlmResponse response = llmProxyService.processLlmRequest(buildTextRequest());

                assertTrue(response.isSuccess());
                assertEquals("根据[ENTITY_1]的电话号码分析，建议联系[ENTITY_1]本人", response.getOriginalResponse());
                assertEquals("根据张三的电话号码分析，建议联系张三本人", response.getDesensitizedResponse());
        }

        /**
         * 泄露验证（核心）：发送给云端 LLM 的 prompt 不得包含原始手机号/人名，
         * 必须以 [ENTITY_1] 和 [PHONE_NUMBER] 占位符形式出现。
         */
        @Test
        void agentFlow_shouldNotSendRawSensitiveDataToLlm() {
                stubCommonDeps();
                when(nlpScanner.extractEntities(anyString())).thenReturn(List.of("张三"));
                when(deepSeekClient.sendRequest(anyString(), any(), any())).thenReturn("好的");

                llmProxyService.processLlmRequest(buildTextRequest());

                ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
                verify(deepSeekClient).sendRequest(promptCaptor.capture(), any(), any());
                String sentPrompt = promptCaptor.getValue();

                assertFalse(sentPrompt.contains("13800138000"), "手机号泄露至云端 LLM");
                assertFalse(sentPrompt.contains("张三"), "人名泄露至云端 LLM");
                assertTrue(sentPrompt.contains("[ENTITY_1]"), "人名应替换为语义占位符");
                assertTrue(sentPrompt.contains("[PHONE_NUMBER]"), "手机号应替换为脱敏占位符");
        }

        /**
         * LLM 响应不含占位符时，原样透传不破坏内容。
         */
        @Test
        void agentFlow_shouldPassThroughResponse_whenNoPlaceholderInLlmResponse() {
                stubCommonDeps();
                when(nlpScanner.extractEntities(anyString())).thenReturn(List.of("张三"));
                when(deepSeekClient.sendRequest(anyString(), any(), any())).thenReturn("这是一个完全正常的回答");

                LlmResponse response = llmProxyService.processLlmRequest(buildTextRequest());

                assertEquals("这是一个完全正常的回答", response.getDesensitizedResponse());
        }

        // ==================== Agent 降级 / 异常边界 ====================

        /**
         * Agent 未识别出语义实体（返回空列表）时，回退使用基础脱敏内容，
         * 不得引入 [ENTITY_N] 占位符。
         */
        @Test
        void agentFlow_shouldFallbackToBaseDesensitized_whenAgentDetectsNothing() {
                stubCommonDeps();
                when(nlpScanner.extractEntities(anyString())).thenReturn(List.of());
                when(deepSeekClient.sendRequest(anyString(), any(), any())).thenReturn("好的");

                llmProxyService.processLlmRequest(buildTextRequest());

                verify(deepSeekClient).sendRequest(eq(BASE_DESENSITIZED), any(), any());
                verify(nlpScanner, never()).checkSafety(anyString());
        }

        /**
         * LLM 调用失败时，返回 success=false 且不抛未捕获异常，
         * 调用方收到失败响应而非崩溃。
         */
        @Test
        void agentFlow_shouldReturnFailure_whenLlmCallFails() {
                stubCommonDeps();
                when(nlpScanner.extractEntities(anyString())).thenReturn(List.of());
                when(deepSeekClient.sendRequest(anyString(), any(), any()))
                                .thenThrow(new RuntimeException("API连接超时"));

                LlmResponse response = llmProxyService.processLlmRequest(buildTextRequest());

                assertFalse(response.isSuccess());
                assertNotNull(response.getErrorMessage());
        }

        /**
         * 关键泄露场景：请求1 在 desensitize 填充映射后 LLM 调用异常中断（restore 未执行），
         * 请求2（无实体、走提前返回分支）不得把响应还原成请求1 的敏感数据。
         * 修复前：请求2 的 [ENTITY_1] 会被还原成请求1 的"张三"（泄露）。
         */
        @Test
        void agentFlow_shouldNotLeakPreviousRequest_whenLlmFailsMidway() {
                // —— 请求1：用户A，Agent 检测到"张三"，但 LLM 调用失败中断 ——
                stubCommonDeps();
                when(nlpScanner.extractEntities(anyString())).thenReturn(List.of("张三"));
                // 注意：重新 stub 时必须用 doReturn/doThrow 风格，否则 when(...) 中
                // 对已 stub 的 thenThrow 方法调用会立即抛出异常。
                doThrow(new RuntimeException("API挂掉"))
                                .when(deepSeekClient).sendRequest(anyString(), any(), any());

                LlmResponse r1 = llmProxyService.processLlmRequest(buildTextRequest());
                assertFalse(r1.isSuccess(), "请求1应失败");

                // —— 请求2：用户B，Agent 降级返回 null（提前返回分支），LLM 响应含 [ENTITY_1] ——
                doReturn("关于[ENTITY_1]的问题请稍后")
                                .when(deepSeekClient).sendRequest(anyString(), any(), any());
                when(nlpScanner.extractEntities(anyString())).thenReturn(null);

                LlmResponse r2 = llmProxyService.processLlmRequest(
                                LlmRequest.builder()
                                                .prompt("B 的普通问题")
                                                .provider(LlmProvider.DEEPSEEK)
                                                .sessionId("session-B")
                                                .dataType("TEXT")
                                                .build());

                assertEquals("关于[ENTITY_1]的问题请稍后", r2.getDesensitizedResponse(),
                                "跨请求泄露！用户B的响应被还原成了用户A的敏感数据");
        }

        /**
         * 多用户场景：用户A 与用户B 均有实体，B 的响应必须用 B 自己的映射还原。
         */
        @Test
        void agentFlow_shouldUseCurrentUserMapping_acrossUsers() {
                stubCommonDeps();
                when(nlpScanner.extractEntities(anyString()))
                                .thenReturn(List.of("张三"))
                                .thenReturn(List.of("李四"));
                when(deepSeekClient.sendRequest(anyString(), any(), any()))
                                .thenReturn("[ENTITY_1]您好，欢迎回来")
                                .thenReturn("[ENTITY_1]您好，请查收");

                // 用户A
                LlmResponse rA = llmProxyService.processLlmRequest(
                                LlmRequest.builder()
                                                .prompt("张三的信息")
                                                .provider(LlmProvider.DEEPSEEK)
                                                .sessionId("session-A")
                                                .dataType("TEXT")
                                                .build());
                // 用户B
                LlmResponse rB = llmProxyService.processLlmRequest(
                                LlmRequest.builder()
                                                .prompt("李四的信息")
                                                .provider(LlmProvider.DEEPSEEK)
                                                .sessionId("session-B")
                                                .dataType("TEXT")
                                                .build());

                assertEquals("张三您好，欢迎回来", rA.getDesensitizedResponse());
                assertEquals("李四您好，请查收", rB.getDesensitizedResponse(),
                                "用户B的响应不得还原成用户A的实体");
        }

        // ==================== Agent 自反思审计 ====================

        /**
         * Agent 检测到语义实体时，必须触发自反思审计（checkSafety）。
         */
        @Test
        void agentSelfReflection_shouldBeInvoked_whenAiEntitiesExist() {
                stubCommonDeps();
                when(nlpScanner.extractEntities(anyString())).thenReturn(List.of("张三"));
                when(deepSeekClient.sendRequest(anyString(), any(), any())).thenReturn("好的");

                llmProxyService.processLlmRequest(buildTextRequest());

                verify(nlpScanner).checkSafety(anyString());
        }

        // ==================== 私有辅助 ====================

        private void stubCommonDeps() {
                DesensitizationResponse base = DesensitizationResponse.builder()
                                .originalContent(RAW_PROMPT)
                                .desensitizedContent(BASE_DESENSITIZED)
                                .detectedEntities(List.of(
                                                new SensitiveEntity(SensitiveType.PHONE_NUMBER, "13800138000", 6, 17)))
                                .success(true)
                                .message("脱敏处理成功")
                                .build();

                when(configService.getConfigOrDefault(any())).thenReturn(config);
                when(configService.isProviderEnabled(any())).thenReturn(true);
                when(desensitizationManager.process(any())).thenReturn(base);
                when(deepSeekClient.supportsDataType("TEXT")).thenReturn(true);
        }

        private LlmRequest buildTextRequest() {
                return LlmRequest.builder()
                                .prompt(RAW_PROMPT)
                                .provider(LlmProvider.DEEPSEEK)
                                .sessionId("session-A")
                                .dataType("TEXT")
                                .build();
        }
}
