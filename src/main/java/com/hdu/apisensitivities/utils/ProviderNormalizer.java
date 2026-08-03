package com.hdu.apisensitivities.utils;

import java.util.Locale;

/**
 * 外部 LLM 平台标准化。
 * <p>
 * 将插件（网页访问）与网关 API 上报的零散供应商标识（DEEPSEEK / DeepSeek / ChatGPT / sk-... 等）
 * 归一为统一的标准平台分组，支撑监控页面按平台类型（OpenAI、Anthropic、通义千问、文心一言等）
 * 完成维度拆分与 LLM 服务分布可视化。
 * </p>
 */
public final class ProviderNormalizer {

    private ProviderNormalizer() {
    }

    /** 标准平台标识 + 展示名 */
    public record ProviderInfo(String code, String name) {
    }

    public static final ProviderInfo UNKNOWN = new ProviderInfo("OTHER", "其他 / 未识别");

    private record Alias(String code, String name, String[] aliases) {
    }

    private static final Alias[] PROVIDERS = {
            new Alias("OPENAI", "OpenAI (ChatGPT)", new String[] { "openai", "chatgpt", "gpt-", "sk-", "pk-" }),
            new Alias("ANTHROPIC", "Anthropic (Claude)", new String[] { "claude", "anthropic" }),
            new Alias("DEEPSEEK", "DeepSeek", new String[] { "deepseek", "ds-" }),
            new Alias("QWEN", "通义千问", new String[] { "qwen", "通义", "dashscope", "aliyun", "tongyi" }),
            new Alias("ERNIE", "文心一言", new String[] { "ernie", "文心", "wenxin", "baidu", "yiyan" }),
            new Alias("DOUBAO", "豆包", new String[] { "doubao", "豆包", "volc", "volcengine", "douyin" }),
            new Alias("KIMI", "Kimi (月之暗面)", new String[] { "kimi", "moonshot", "月之暗面" }),
            new Alias("HUNYUAN", "腾讯混元", new String[] { "hunyuan", "混元", "tencent" }),
            new Alias("GEMINI", "Gemini", new String[] { "gemini", "bard", "google" }),
            new Alias("PERPLEXITY", "Perplexity", new String[] { "perplexity" }),
            new Alias("OLLAMA", "本地模型 (Ollama)", new String[] { "ollama", "localhost", "本地", "local" }),
    };

    /**
     * 将原始供应商标识标准化为平台分组。未匹配返回 {@link #UNKNOWN}。
     */
    public static ProviderInfo normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        for (Alias p : PROVIDERS) {
            for (String a : p.aliases()) {
                if (v.contains(a)) {
                    return new ProviderInfo(p.code(), p.name());
                }
            }
        }
        return UNKNOWN;
    }
}
