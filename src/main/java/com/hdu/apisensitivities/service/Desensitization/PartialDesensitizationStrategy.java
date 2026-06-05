package com.hdu.apisensitivities.service.Desensitization;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.utils.CollectionTypeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PartialDesensitizationStrategy implements DesensitizationStrategy {

    @Autowired
    private GlobalSessionContextRepository contextRepository;

    @Override
    public String desensitize(String text, List<SensitiveEntity> sensitiveEntities) {
        if (sensitiveEntities.isEmpty()) {
            return text;
        }

        // 过滤掉没有正确位置信息的实体（例如来自结构化数据的实体）
        List<SensitiveEntity> validEntities = sensitiveEntities.stream()
                .filter(entity -> entity.getStart() >= 0 && entity.getEnd() <= text.length()
                        && entity.getStart() <= entity.getEnd())
                .sorted((e1, e2) -> Integer.compare(e2.getStart(), e1.getStart()))
                .collect(Collectors.toList());

        if (validEntities.isEmpty()) {
            return text;
        }

        String result = text;
        String sessionId = DesensitizeRequestContext.getSessionId();
        for (SensitiveEntity entity : validEntities) {
            try {
                String originalText = entity.getOriginalText();
                String typeStr = entity.getType().name();

                String maskedText = contextRepository.getOrCreateConsistencyValue(sessionId, originalText, typeStr,
                        currentId -> "[" + applyPartialMask(originalText, entity.getType()) + "_" + currentId + "]");

                int start = Math.max(0, entity.getStart());
                int end = Math.min(text.length(), entity.getEnd());
                if (start <= end) {
                    result = result.substring(0, start) + maskedText + result.substring(end);
                }
            } catch (StringIndexOutOfBoundsException e) {
                log.warn("脱敏过程中出现索引越界，实体: {}, 文本长度: {}", entity, text.length());
                // 忽略这个实体，继续处理下一个
            }
        }

        return result;
    }

    @Override
    public Map<String, Object> desensitizeStructuredData(Map<String, Object> structuredData,
            List<SensitiveEntity> sensitiveEntities) {
        if (sensitiveEntities.isEmpty() || structuredData == null) {
            return structuredData;
        }

        // 创建一个新的Map来存储脱敏后的数据
        Map<String, Object> result = new HashMap<>(structuredData);

        // 为每个敏感实体应用脱敏
        for (SensitiveEntity entity : sensitiveEntities) {
            // 尝试找到包含敏感数据的字段并脱敏
            if (entity.getMetadata() != null && entity.getMetadata().containsKey("fieldPath")) {
                String fieldPath = (String) entity.getMetadata().get("fieldPath");
                desensitizeFieldInMap(result, fieldPath, entity.getType());
            } else {
                // 如果没有字段路径信息，进行深度遍历
                result = deepDesensitizeMap(result, entity);
            }
        }

        return result;
    }

    @Override
    public byte[] desensitizeBinaryData(byte[] binaryData, String dataType, List<SensitiveEntity> sensitiveEntities) {
        // 二进制数据脱敏通常比较复杂，这里提供一个基本实现
        // 对于图片、音频等二进制数据，实际实现可能需要OCR识别文字内容后再脱敏
        // 这里返回原数据，表示不进行脱敏处理
        log("二进制数据[{}]脱敏请求已接收，当前策略不支持直接脱敏", dataType);
        return binaryData;
    }

    @Override
    public Set<String> supportedDataTypes() {
        return new HashSet<>(Arrays.asList("TEXT", "JSON", "XML", "PDF", "DOC", "DOCX", "EXCEL"));
    }

    @Override
    public boolean supportsDataType(String dataType) {
        return supportedDataTypes().contains(dataType.toUpperCase());
    }

    @Override
    public String getName() {
        return "partialDesensitizationStrategy";
    }

    private String applyPartialMask(String original, SensitiveType type) {
        switch (type) {
            case PHONE_NUMBER:
                return original.length() > 7 ? original.substring(0, 3) + "****" + original.substring(7) : "****";
            case BANK_CARD:
                return original.length() > 4 ? "****" + original.substring(original.length() - 4) : "****";
            case EMAIL:
                int atIndex = original.indexOf('@');
                return atIndex > 0 ? original.substring(0, 2) + "***" + original.substring(atIndex) : "***@***";
            case IP_ADDRESS:
                if (original.contains(".")) {
                    String[] parts = original.split("\\.");
                    if (parts.length == 4) {
                        return parts[0] + "." + parts[1] + ".*.*";
                    }
                    return "*.*.*.*";
                } else if (original.contains(":")) {
                    String[] segs = original.split(":");
                    if (segs.length >= 3) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(segs[0]).append(":").append(segs[1]).append(":");
                        sb.append("****");
                        if (segs.length > 3)
                            sb.append(":****");
                        sb.append(":").append(segs[segs.length - 1]);
                        return sb.toString();
                    }
                    return "[IP]";
                }
                return "[IP]";
            default:
                return "***";
        }
    }

    @Override
    public Set<SensitiveType> supportedTypes() {
        return new HashSet<>(Arrays.asList(SensitiveType.PHONE_NUMBER, SensitiveType.BANK_CARD, SensitiveType.EMAIL,
                SensitiveType.IP_ADDRESS));
    }

    // 脱敏Map中的特定字段
    private void desensitizeFieldInMap(Map<String, Object> map, String fieldPath, SensitiveType type) {
        String[] pathParts = fieldPath.split("\\.");
        if (pathParts.length == 0) {
            return;
        }

        Object current = map;
        for (int i = 0; i < pathParts.length - 1; i++) {
            String part = pathParts[i];
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return; // 路径不存在
            }
        }

        if (current instanceof Map && pathParts.length > 0) {
            String fieldName = pathParts[pathParts.length - 1];
            Object value = ((Map<?, ?>) current).get(fieldName);
            if (value instanceof String) {
                Map<String, Object> currentMap = CollectionTypeUtils.asStringObjectMap(current);
                if (currentMap != null) {
                    currentMap.put(fieldName, applyPartialMask((String) value, type));
                }
            }
        }
    }

    // 深度遍历Map进行脱敏
    private Map<String, Object> deepDesensitizeMap(Map<String, Object> map, SensitiveEntity entity) {
        Map<String, Object> result = new HashMap<>();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof String) {
                String strValue = (String) value;
                // 检查是否包含敏感信息
                if (strValue.contains(entity.getOriginalText())) {
                    result.put(key, applyPartialMask(strValue, entity.getType()));
                } else {
                    result.put(key, strValue);
                }
            } else if (value instanceof Map) {
                // 递归处理嵌套Map
                Map<String, Object> nestedMap = CollectionTypeUtils.asStringObjectMap(value);
                result.put(key, nestedMap == null ? value : deepDesensitizeMap(nestedMap, entity));
            } else if (value instanceof List) {
                // 处理List
                result.put(key, deepDesensitizeList((List<?>) value, entity));
            } else {
                // 其他类型直接保留
                result.put(key, value);
            }
        }

        return result;
    }

    // 深度遍历List进行脱敏
    private List<Object> deepDesensitizeList(List<?> list, SensitiveEntity entity) {
        List<Object> result = new ArrayList<>();

        for (Object item : list) {
            if (item instanceof String) {
                String strItem = (String) item;
                if (strItem.contains(entity.getOriginalText())) {
                    result.add(applyPartialMask(strItem, entity.getType()));
                } else {
                    result.add(strItem);
                }
            } else if (item instanceof Map) {
                Map<String, Object> nestedMap = CollectionTypeUtils.asStringObjectMap(item);
                result.add(nestedMap == null ? item : deepDesensitizeMap(nestedMap, entity));
            } else if (item instanceof List) {
                result.add(deepDesensitizeList((List<?>) item, entity));
            } else {
                result.add(item);
            }
        }

        return result;
    }

    // 添加日志方法
    private void log(String message, Object... args) {
        log.info("[PartialDesensitizationStrategy] " + message, args);
    }
}
