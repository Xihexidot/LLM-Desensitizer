package com.hdu.apisensitivities.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DesensitizationRequest {
    private String content; // 文本内容
    private Map<String, Object> structuredData; // 结构化数据（JSON、XML等）
    private byte[] binaryData; // 二进制数据（图片、语音等）
    private String dataType; // 数据类型："TEXT", "JSON", "XML", "IMAGE", "AUDIO", "PDF", "DOC", "EXCEL"
    private String language; // "zh", "en", "mixed"
    private List<String> blacklist; // 本次请求的黑名单
    private List<String> whitelist; // 本次请求的白名单
    private boolean strictMode;
    private String strategy; // 指定脱敏策略
    private Double confidenceThreshold; // 置信度阈值
    private Map<String, Object> metadata; // 元数据（如文件名、格式等）
    private boolean preserveStructure; // 是否保留原始数据结构
    private Set<String> includeTypes; // 需要检测的敏感类型集合
    @Builder.Default
    private boolean autoScenarioDetection = false; // 是否开启自动情景感知，默认关闭
    private String manualScenarioType; // 用户手动指定的情景类型
    private String sessionId; // 会话唯一标识，用于标识同一次对话（如前端生成的 UUID 或聊天会话 ID）
    
    // 判断是否为结构化数据
    public boolean isStructuredData() {
        return structuredData != null && !structuredData.isEmpty() ||
                "JSON".equals(dataType) || "XML".equals(dataType);
    }

    // 判断是否为二进制数据
    public boolean isBinaryData() {
        return binaryData != null && binaryData.length > 0 ||
                "IMAGE".equals(dataType) || "AUDIO".equals(dataType) ||
                "PDF".equals(dataType) || "DOC".equals(dataType) ||
                "EXCEL".equals(dataType);
    }

    // 获取主要内容（用于向后兼容）
    public String getMainContent() {
        if (content != null) {
            return content;
        } else if (structuredData != null) {
            return structuredData.toString();
        }
        return null;
    }
}
