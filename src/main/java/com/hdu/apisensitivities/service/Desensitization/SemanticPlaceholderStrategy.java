package com.hdu.apisensitivities.service.Desensitization;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import org.springframework.stereotype.Component;
import java.util.*;

@Component("semanticPlaceholderStrategy")
public class SemanticPlaceholderStrategy implements DesensitizationStrategy {

    // 🌟 关键：使用 ThreadLocal 存储映射表，确保并发安全（每个请求有自己的小票存根）
    private final ThreadLocal<Map<String, String>> mappingTable = ThreadLocal.withInitial(LinkedHashMap::new);

    @Override
    public String desensitize(String text, List<SensitiveEntity> sensitiveEntities) {
        return "";
    }

    @Override
    public Map<String, Object> desensitizeStructuredData(Map<String, Object> structuredData, List<SensitiveEntity> sensitiveEntities) {
        return Map.of();
    }

    @Override
    public byte[] desensitizeBinaryData(byte[] binaryData, String dataType, List<SensitiveEntity> sensitiveEntities) {
        return new byte[0];
    }

    @Override
    public Set<SensitiveType> supportedTypes() {
        return Set.of();
    }

    @Override
    public Set<String> supportedDataTypes() {
        return Set.of();
    }

    @Override
    public boolean supportsDataType(String dataType) {
        return false;
    }

    @Override
    public String getName() {
        // 这个名字通常用于在策略工厂中标识自己
        return "SEMANTIC_PLACEHOLDER";
    }
    public String desensitize(String text, Object... args) {
        if (text == null || args.length == 0 || !(args[0] instanceof List)) {
            return text;
        }

        List<String> entities = (List<String>) args[0];
        // 🌟 避坑指南：先按长度降序排列，防止“李华”把“李华强”切断
        entities.sort((a, b) -> Integer.compare(b.length(), a.length()));

        String maskedText = text;
        Map<String, String> currentMap = mappingTable.get();
        currentMap.clear();

        int index = 1;
        for (String entity : entities) {
            String placeholder = "[ENTITY_" + index + "]";
            currentMap.put(placeholder, entity);
            maskedText = maskedText.replace(entity, placeholder);
            index++;
        }
        return maskedText;
    }

    // 🌟 核心功能：还原逻辑
    public String restore(String aiResponse) {
        String restoredText = aiResponse;
        Map<String, String> currentMap = mappingTable.get();

        for (Map.Entry<String, String> entry : currentMap.entrySet()) {
            restoredText = restoredText.replace(entry.getKey(), entry.getValue());
        }
        return restoredText;
    }
}
