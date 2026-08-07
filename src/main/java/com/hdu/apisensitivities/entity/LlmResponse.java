package com.hdu.apisensitivities.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {
    private String originalResponse;
    private String desensitizedResponse;
    private List<SensitiveEntity> inputSensitiveEntities;
    private List<SensitiveEntity> outputSensitiveEntities;
    private LlmProvider provider;
    private String model;
    private Long processingTimeMs;
    private boolean success;
    private String errorMessage;
    private String dataType; // 响应数据类型
    private Map<String, Object> structuredResponse; // 结构化响应数据
    private byte[] binaryResponse; // 二进制响应数据（如处理后的图片等）

    /**
     * AI 链路的脱敏标记 → 原始数据 映射，用于前端解码还原。
     * <p>
     * 合并两类映射：
     * <ul>
     *   <li>基础脱敏标记（如 {@code [PHONE_1]}）→ 原始明文（来自会话级缓存反向导出）；</li>
     *   <li>AI 语义脱敏标记（如 {@code [ENTITY_1]}）→ 语义实体文本（来自 {@code SemanticPlaceholderStrategy}）。</li>
     * </ul>
     * 前端据此可将 AI 答复内容中的脱敏标记完整还原为发送前的原始数据格式。
     * </p>
     */
    private Map<String, String> maskMapping;

    public ApiResponse toApiResponse() {
        return ApiResponse.builder()
                .originalResponse(this.originalResponse)
                .desensitizedResponse(this.desensitizedResponse)
                .inputSensitiveEntities(this.inputSensitiveEntities)
                .outputSensitiveEntities(this.outputSensitiveEntities)
                .build();
    }
    
    // 判断是否为结构化响应
    public boolean isStructuredResponse() {
        return structuredResponse != null && !structuredResponse.isEmpty();
    }
    
    // 判断是否为二进制响应
    public boolean isBinaryResponse() {
        return binaryResponse != null && binaryResponse.length > 0;
    }
}
