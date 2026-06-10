package com.hdu.apisensitivities.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表示发送到 LLM 服务的请求信息。
 * <p>
 * 包含模型、提供商、输入提示、参数、会话 ID、黑白名单以及数据类型等字段。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {
    /**
     * 待发送给模型的提示文本。
     */
    private String prompt;

    /**
     * LLM 提供商。若为 {@code null}，则默认使用 {@link LlmProvider#DEEPSEEK}。
     */
    private LlmProvider provider;

    /**
     * 模型标识，例如模型名称或编号。
     */
    private String model;

    /**
     * 模型调用参数，例如温度、最大长度等可选配置。
     */
    private Map<String, Object> parameters;

    /**
     * 会话 ID，用于在多轮交互中区分请求上下文。
     */
    private String sessionId;

    /**
     * 黑名单词汇列表，用于在生成或处理结果时过滤敏感内容。
     */
    private List<String> blacklist;

    /**
     * 白名单词汇列表，用于允许通过的内容或特殊处理。
     */
    private List<String> whitelist;

    /**
     * 请求数据类型，例如 {@code TEXT}, {@code JSON}, {@code XML}, {@code IMAGE},
     * {@code AUDIO}, {@code PDF}, {@code DOC} 等。
     */
    private String dataType;

    /**
     * 指定需要检测的敏感类型集合（为空则检测全部）。
     * 网关层情景感知后传入，用于动态调整检测范围。
     */
    private Set<String> includeTypes;

    /**
     * 如果未指定 provider，则返回默认提供商 {@link LlmProvider#DEEPSEEK}。
     *
     * @return 有效的 LLM 提供商
     */
    public LlmProvider getProvider() {
        return provider != null ? provider : LlmProvider.DEEPSEEK;
    }

    /**
     * 如果未指定数据类型，则默认返回 {@code TEXT}。
     *
     * @return 有效的数据类型字符串
     */
    public String getDataType() {
        return dataType != null ? dataType : "TEXT";
    }

    /**
     * 从 API 请求对象构造默认的 {@code LlmRequest}。
     *
     * @param apiRequest 原始 API 请求对象
     * @return 构造好的 {@code LlmRequest} 实例
     */
    public static LlmRequest fromApiRequest(ApiRequest apiRequest) {
        return LlmRequest.builder()
                .prompt(apiRequest.getPrompt())
                .provider(LlmProvider.DEEPSEEK)
                .model(apiRequest.getModel())
                .parameters(apiRequest.getParameters())
                .sessionId(apiRequest.getSessionId())
                .dataType("TEXT")
                .build();
    }
}
