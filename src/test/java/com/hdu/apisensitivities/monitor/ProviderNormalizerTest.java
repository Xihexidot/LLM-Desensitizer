package com.hdu.apisensitivities.monitor;

import com.hdu.apisensitivities.utils.ProviderNormalizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 平台标准化单元测试。
 * 验证插件/网关上报的零散供应商标识能正确归一为 12 个标准平台分组。
 */
class ProviderNormalizerTest {

    @Test
    void openaiAliases_mapToOpenai() {
        for (String raw : new String[] { "OpenAI", "openai", "ChatGPT", "chatgpt", "gpt-4o", "sk-proj-abc",
                "pk-live" }) {
            assertEquals("OPENAI", ProviderNormalizer.normalize(raw).code(), "raw=" + raw);
            assertEquals("OpenAI (ChatGPT)", ProviderNormalizer.normalize(raw).name(), "raw=" + raw);
        }
    }

    @Test
    void anthropicAliases_mapToAnthropic() {
        for (String raw : new String[] { "Claude", "claude-3-5-sonnet", "anthropic", "Anthropic API" }) {
            assertEquals("ANTHROPIC", ProviderNormalizer.normalize(raw).code(), "raw=" + raw);
        }
    }

    @Test
    void deepseekAliases_mapToDeepseek() {
        for (String raw : new String[] { "DeepSeek", "deepseek-chat", "ds-pro" }) {
            assertEquals("DEEPSEEK", ProviderNormalizer.normalize(raw).code(), "raw=" + raw);
        }
    }

    @Test
    void qwenAliases_mapToQwen() {
        for (String raw : new String[] { "通义千问", "通义", "qwen-max", "dashscope", "aliyun" }) {
            assertEquals("QWEN", ProviderNormalizer.normalize(raw).code(), "raw=" + raw);
        }
    }

    @Test
    void ernieAliases_mapToErnie() {
        for (String raw : new String[] { "文心一言", "文心", "ernie-4.0", "wenxin", "baidu" }) {
            assertEquals("ERNIE", ProviderNormalizer.normalize(raw).code(), "raw=" + raw);
        }
    }

    @Test
    void doubaoAliases_mapToDoubao() {
        for (String raw : new String[] { "豆包", "doubao-pro", "volcengine", "volc" }) {
            assertEquals("DOUBAO", ProviderNormalizer.normalize(raw).code(), "raw=" + raw);
        }
    }

    @Test
    void kimiAliases_mapToKimi() {
        for (String raw : new String[] { "Kimi", "kimi-k2", "moonshot", "月之暗面" }) {
            assertEquals("KIMI", ProviderNormalizer.normalize(raw).code(), "raw=" + raw);
        }
    }

    @Test
    void hunyuanAndGeminiAndOthers_mapCorrectly() {
        assertEquals("HUNYUAN", ProviderNormalizer.normalize("混元").code());
        assertEquals("HUNYUAN", ProviderNormalizer.normalize("hunyuan").code());
        assertEquals("GEMINI", ProviderNormalizer.normalize("Gemini").code());
        assertEquals("GEMINI", ProviderNormalizer.normalize("google-gemini").code());
        assertEquals("PERPLEXITY", ProviderNormalizer.normalize("Perplexity").code());
        assertEquals("OLLAMA", ProviderNormalizer.normalize("ollama").code());
        assertEquals("OLLAMA", ProviderNormalizer.normalize("localhost:11434").code());
    }

    @Test
    void caseInsensitive_containsMatching() {
        assertEquals("DEEPSEEK", ProviderNormalizer.normalize("  DEEPSEEK-API  ").code());
        assertEquals("OPENAI", ProviderNormalizer.normalize("CHATGPT").code());
        assertEquals("OPENAI", ProviderNormalizer.normalize("gpt-4o-mini").code());
    }

    @Test
    void unknownOrBlank_returnsOther() {
        assertEquals("OTHER", ProviderNormalizer.normalize("mystery-llm-xyz").code());
        assertEquals("OTHER", ProviderNormalizer.normalize("unknown.vendor").code());
        assertEquals("OTHER", ProviderNormalizer.normalize(null).code());
        assertEquals("OTHER", ProviderNormalizer.normalize("").code());
        assertEquals("OTHER", ProviderNormalizer.normalize("   ").code());
    }

    @Test
    void unknownDiffersFromKnown() {
        assertNotEquals(ProviderNormalizer.normalize("DeepSeek").code(),
                ProviderNormalizer.normalize("mystery-vendor").code());
    }
}
