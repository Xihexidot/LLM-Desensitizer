package com.hdu.apisensitivities.service.SensitiveDetection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NlpScannerTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private NlpScanner scanner;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 注入默认模型名，避免 @Value 依赖 Spring 上下文
        ReflectionTestUtils.setField(scanner, "modelName", "qwen:1.8b");
    }

    // ==================== extractEntities 测试 ====================

    @Test
    void extractEntities_shouldReturnList_whenOllamaReturnsCommaSeparated() throws Exception {
        String ollamaResponse = objectMapper.writeValueAsString(
                Map.of("response", "张三, 阿里巴巴, 飞天项目"));
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn(ollamaResponse);

        List<String> result = scanner.extractEntities("张三在阿里巴巴负责飞天项目");

        assertEquals(3, result.size());
        assertTrue(result.contains("张三"));
        assertTrue(result.contains("阿里巴巴"));
        assertTrue(result.contains("飞天项目"));
    }

    @Test
    void extractEntities_shouldReturnEmptyList_whenOllamaReturnsNone() throws Exception {
        String ollamaResponse = objectMapper.writeValueAsString(
                Map.of("response", "无"));
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn(ollamaResponse);

        List<String> result = scanner.extractEntities("今天是星期一，天气很好");

        assertTrue(result.isEmpty());
    }

    @Test
    void extractEntities_shouldReturnEmptyList_whenInputIsNull() {
        List<String> result = scanner.extractEntities(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void extractEntities_shouldReturnEmptyList_whenInputIsBlank() {
        List<String> result = scanner.extractEntities("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractEntities_shouldHandleChineseAndEnglishCommas() throws Exception {
        String response = objectMapper.writeValueAsString(
                Map.of("response", "Alice, Bob，Charlie，David"));
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn(response);

        List<String> result = scanner.extractEntities("Alice and Bob");
        // "中"和英"逗号混合 → split 前会被统一替换
        assertEquals(4, result.size());
    }

    @Test
    void extractEntities_shouldFilterOutSingleCharacterWords() throws Exception {
        String response = objectMapper.writeValueAsString(
                Map.of("response", "张, 王, 李赵"));
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn(response);

        List<String> result = scanner.extractEntities("张王李赵");
        // "张", "王" 都是单字，被 filter 掉
        assertEquals(1, result.size());
        assertTrue(result.contains("李赵"));
    }

    @Test
    void extractEntities_shouldDeduplicate() throws Exception {
        String response = objectMapper.writeValueAsString(
                Map.of("response", "张三, 张三, 李四"));
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn(response);

        List<String> result = scanner.extractEntities("张三李四张三");
        assertEquals(2, result.size());
    }

    @Test
    void extractEntities_shouldReturnEmpty_whenOllamaReturnsMalformedJson() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn("这不是 JSON");

        List<String> result = scanner.extractEntities("test");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractEntities_shouldReturnEmpty_whenOllamaThrowsException() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        List<String> result = scanner.extractEntities("test");
        assertTrue(result.isEmpty());
    }

    // ==================== checkSafety 测试 ====================

    @Test
    void checkSafety_shouldReturnTrue_whenOllamaSaysDangerous() throws Exception {
        String response = objectMapper.writeValueAsString(
                Map.of("response", "危险，文本中仍包含真实人名"));
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn(response);

        boolean result = scanner.checkSafety("张三的电话是[PHONE_1]");

        assertTrue(result);
    }

    @Test
    void checkSafety_shouldReturnFalse_whenOllamaSaysSafe() throws Exception {
        String response = objectMapper.writeValueAsString(
                Map.of("response", "安全，所有敏感信息已被脱敏"));
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn(response);

        boolean result = scanner.checkSafety("[NAME_1]的电话是[PHONE_1]");

        assertFalse(result);
    }

    @Test
    void checkSafety_shouldReturnTrue_whenOllamaSaysDANGEROUS() throws Exception {
        String response = objectMapper.writeValueAsString(
                Map.of("response", "DANGEROUS: Contains real name"));
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn(response);

        boolean result = scanner.checkSafety("张三 is here");

        assertTrue(result);
    }

    @Test
    void checkSafety_shouldReturnFalse_whenInputIsNull() {
        boolean result = scanner.checkSafety(null);
        assertFalse(result);
    }

    @Test
    void checkSafety_shouldReturnFalse_whenInputIsEmpty() {
        boolean result = scanner.checkSafety("");
        assertFalse(result);
    }

    @Test
    void checkSafety_shouldReturnFalse_whenOllamaThrowsException() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("Ollama not running"));

        boolean result = scanner.checkSafety("test content");
        assertFalse(result);
    }

    @Test
    void checkSafety_shouldUseRagMode_whenPromptContainsSystemDirective() throws Exception {
        String ragPrompt = "【系统指令】你是一个客服\n【参考合规法规】数据安全法\n请回答用户的问题";
        String response = objectMapper.writeValueAsString(
                Map.of("response", "安全"));
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn(response);

        // RAG 模式下，Agent 审计的是整个 Prompt（包含合规法规）
        boolean result = scanner.checkSafety(ragPrompt);

        assertFalse(result);
    }

    @Test
    void checkSafety_shouldReturnFalse_whenOllamaResponseMissingResponseField() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn("{}");

        boolean result = scanner.checkSafety("test");
        // JSON 缺少 "response" 字段 → 抛 NullPointerException → catch → 返回 false
        assertFalse(result);
    }

    // ==================== processRawAiString 辅助方法（通过反射测试） ====================

    @Test
    void processRawAiString_shouldReturnEmpty_whenContainsNo() throws Exception {
        // 代码检查 raw 是否包含"无"（表示"无敏感信息"）
        List<String> result = invokeProcessRawAiString("无敏感信息");
        assertTrue(result.isEmpty());
    }

    @Test
    void processRawAiString_shouldReturnEmpty_whenContainsTheNoCharacter() throws Exception {
        List<String> result = invokeProcessRawAiString("无");
        assertTrue(result.isEmpty());
    }

    @Test
    void processRawAiString_shouldReturnEmpty_whenNull() throws Exception {
        List<String> result = invokeProcessRawAiString(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void processRawAiString_shouldReturnEmpty_whenShortLength() throws Exception {
        List<String> result = invokeProcessRawAiString("a");
        assertTrue(result.isEmpty());
    }

    @Test
    void processRawAiString_shouldParseCommaSeparatedValues() throws Exception {
        // processRawAiString 按逗号分割，不是按换行符
        List<String> result = invokeProcessRawAiString("张三, 李四, 王五");
        assertEquals(3, result.size());
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeProcessRawAiString(String raw) throws Exception {
        java.lang.reflect.Method method = NlpScanner.class.getDeclaredMethod("processRawAiString", String.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(scanner, raw);
    }
}
