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
