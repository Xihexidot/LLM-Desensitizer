package com.hdu.apisensitivities.service.Desensitization;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MaskDesensitizationStrategy implements DesensitizationStrategy {

    private final Map<SensitiveType, String> MASK_TEMPLATES = new HashMap<>();

    public MaskDesensitizationStrategy() {
        MASK_TEMPLATES.put(SensitiveType.PHONE_NUMBER, "[PHONE]");
        MASK_TEMPLATES.put(SensitiveType.BANK_CARD, "[BANK_CARD]");
        MASK_TEMPLATES.put(SensitiveType.EMAIL, "[EMAIL]");
        MASK_TEMPLATES.put(SensitiveType.ID_CARD, "[ID_CARD]");
        MASK_TEMPLATES.put(SensitiveType.NAME, "[NAME]");
        MASK_TEMPLATES.put(SensitiveType.ADDRESS, "[ADDRESS]");
        MASK_TEMPLATES.put(SensitiveType.CREDIT_CARD, "[CREDIT_CARD]");
        MASK_TEMPLATES.put(SensitiveType.PASSWORD, "[PASSWORD]");
        MASK_TEMPLATES.put(SensitiveType.BIRTH_DATE, "[BIRTH_DATE]");
        MASK_TEMPLATES.put(SensitiveType.CUSTOM, "[CUSTOM]");
        MASK_TEMPLATES.put(SensitiveType.IP_ADDRESS, "[IP]");
        MASK_TEMPLATES.put(SensitiveType.LICENSE_PLATE, "[LICENSE_PLATE]");
    }

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
        for (SensitiveEntity entity : validEntities) {
            try {
                String mask = MASK_TEMPLATES.getOrDefault(entity.getType(), "[MASKED]");
                // 确保索引不越界
                int start = Math.max(0, entity.getStart());
                int end = Math.min(text.length(), entity.getEnd());
                if (start <= end) {
                    result = result.substring(0, start) +
                            mask +
                            result.substring(end);
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
        if (structuredData == null) {
            return null;
        }

        Map<String, Object> dataMap = structuredData;

        // 优先处理带有字段路径的实体
        List<SensitiveEntity> pathEntities = new ArrayList<>();
        List<SensitiveEntity> nonPathEntities = new ArrayList<>();

        // 分类实体
        for (SensitiveEntity entity : sensitiveEntities) {
            if (entity.getMetadata() != null && entity.getMetadata().containsKey("fieldPath")) {
                pathEntities.add(entity);
            } else {
                nonPathEntities.add(entity);
            }
        }

        // 按字段路径处理
        for (SensitiveEntity entity : pathEntities) {
            String fieldPath = (String) entity.getMetadata().get("fieldPath");
            String mask = MASK_TEMPLATES.getOrDefault(entity.getType(), "[MASKED]");
            dataMap = maskFieldInMap(dataMap, fieldPath, entity, mask);
        }

        // 按值进行深度遍历处理
        for (SensitiveEntity entity : nonPathEntities) {
            String mask = MASK_TEMPLATES.getOrDefault(entity.getType(), "[MASKED]");
            dataMap = deepMaskMap(dataMap, entity, mask);
        }

        return dataMap;
    }

    @Override
    public byte[] desensitizeBinaryData(byte[] binaryData, String dataType, List<SensitiveEntity> sensitiveEntities) {
        // 二进制数据脱敏，这里记录请求但返回原数据
        // 实际应用中可能需要根据二进制数据类型进行特定处理
        log.info("[MaskDesensitizationStrategy] 接收到二进制数据[{}]脱敏请求，当前策略不支持直接脱敏", dataType);
        return binaryData;
    }

    @Override
    public Set<String> supportedDataTypes() {
        // 支持文本和结构化数据类型，以及常见的二进制数据类型
        return new HashSet<>(Arrays.asList("TEXT", "JSON", "XML", "PDF", "DOC", "DOCX", "EXCEL"));
    }

    @Override
    public boolean supportsDataType(String dataType) {
        return supportedDataTypes().contains(dataType.toUpperCase());
    }

    @Override
    public String getName() {
        return "maskDesensitizationStrategy";
    }

    @Override
    public Set<SensitiveType> supportedTypes() {
        return new HashSet<>(Arrays.asList(SensitiveType.PHONE_NUMBER, SensitiveType.BANK_CARD, SensitiveType.EMAIL,
                SensitiveType.ID_CARD, SensitiveType.ADDRESS, SensitiveType.NAME, SensitiveType.BIRTH_DATE,
                SensitiveType.PASSWORD, SensitiveType.CREDIT_CARD, SensitiveType.PASSPORT, SensitiveType.IP_ADDRESS,
                SensitiveType.LICENSE_PLATE));
    }

    // 按字段路径对Map中的字段进行掩码处理
    private Map<String, Object> maskFieldInMap(Map<String, Object> map, String fieldPath,
            SensitiveEntity sensitiveEntity, String mask) {
        if (map == null || fieldPath == null || fieldPath.isEmpty()) {
            return map;
        }

        String[] parts = fieldPath.split("\\.");
        return processFieldPath(map, parts, 0, sensitiveEntity, mask);
    }

    // 递归处理字段路径
    private Map<String, Object> processFieldPath(Map<String, Object> map, String[] pathParts, int index,
            SensitiveEntity sensitiveEntity, String mask) {
        if (index >= pathParts.length) {
            return map;
        }

        String part = pathParts[index];
        // 检查是否为数组索引
        if (part.matches("\\[\\d+\\]") && map.containsValue(map)) {
            // 处理数组索引
            int arrayIndex = extractArrayIndex(part);
            for (Object value : map.values()) {
                if (value instanceof List && ((List<?>) value).size() > arrayIndex) {
                    Object listElement = ((List<?>) value).get(arrayIndex);
                    if (index == pathParts.length - 1 && listElement instanceof String) {
                        // 最后一部分且是字符串，进行脱敏
                        String strValue = (String) listElement;
                        ((List<Object>) value).set(arrayIndex,
                                strValue.replace(sensitiveEntity.getOriginalText(), mask));
                    } else if (listElement instanceof Map) {
                        // 嵌套对象，继续递归
                        processFieldPath((Map<String, Object>) listElement, pathParts, index + 1, sensitiveEntity,
                                mask);
                    }
                }
            }
        } else if (map.containsKey(part)) {
            Object value = map.get(part);
            if (index == pathParts.length - 1 && value instanceof String) {
                // 最后一部分且是字符串，进行脱敏
                String strValue = (String) value;
                map.put(part, strValue.replace(sensitiveEntity.getOriginalText(), mask));
            } else if (value instanceof Map) {
                // 嵌套对象，继续递归
                processFieldPath((Map<String, Object>) value, pathParts, index + 1, sensitiveEntity, mask);
            } else if (value instanceof List) {
                // 列表，需要递归处理每个元素
                for (Object element : (List<?>) value) {
                    if (element instanceof Map) {
                        processFieldPath((Map<String, Object>) element, pathParts, index + 1, sensitiveEntity, mask);
                    }
                }
            }
        }

        return map;
    }

    // 提取数组索引
    private int extractArrayIndex(String part) {
        // 例如 "[0]" -> 0
        try {
            return Integer.parseInt(part.substring(1, part.length() - 1));
        } catch (NumberFormatException e) {
            return -1; // 无效索引
        }
    }

    // 深度遍历Map进行掩码替换
    private Map<String, Object> deepMaskMap(Map<String, Object> map, SensitiveEntity entity, String mask) {
        Map<String, Object> result = new HashMap<>();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof String) {
                String strValue = (String) value;
                // 检查是否包含敏感信息
                if (strValue.contains(entity.getOriginalText())) {
                    result.put(key, strValue.replace(entity.getOriginalText(), mask));
                } else {
                    result.put(key, strValue);
                }
            } else if (value instanceof Map) {
                // 递归处理嵌套Map
                result.put(key, deepMaskMap((Map<String, Object>) value, entity, mask));
            } else if (value instanceof List) {
                // 处理List
                result.put(key, deepMaskList((List<?>) value, entity, mask));
            } else {
                // 其他类型直接保留
                result.put(key, value);
            }
        }

        return result;
    }

    // 深度遍历List进行掩码替换
    private List<Object> deepMaskList(List<?> list, SensitiveEntity entity, String mask) {
        List<Object> result = new ArrayList<>();

        for (Object item : list) {
            if (item instanceof String) {
                String strItem = (String) item;
                // 检查是否包含敏感信息
                if (strItem.contains(entity.getOriginalText())) {
                    strItem = strItem.replace(entity.getOriginalText(), mask);
                }
                result.add(strItem);
            } else if (item instanceof Map) {
                // 递归处理嵌套Map
                result.add(deepMaskMap((Map<String, Object>) item, entity, mask));
            } else if (item instanceof List) {
                // 递归处理嵌套List
                result.add(deepMaskList((List<?>) item, entity, mask));
            } else {
                // 其他类型直接保留
                result.add(item);
            }
        }

        return result;
    }

    // 处理多个实体的深度遍历方法
    private List<Object> deepMaskList(List<?> list, List<SensitiveEntity> entities) {
        List<Object> result = new ArrayList<>();

        for (Object item : list) {
            if (item instanceof String) {
                String strItem = (String) item;
                // 对每个字符串项应用所有实体的掩码
                for (SensitiveEntity entity : entities) {
                    String mask = MASK_TEMPLATES.getOrDefault(entity.getType(), "[MASKED]");
                    if (strItem.contains(entity.getOriginalText())) {
                        strItem = strItem.replace(entity.getOriginalText(), mask);
                    }
                }
                result.add(strItem);
            } else if (item instanceof Map) {
                // 递归处理嵌套Map
                Map<String, Object> maskedMap = new HashMap<>((Map<String, Object>) item);
                for (SensitiveEntity entity : entities) {
                    String mask = MASK_TEMPLATES.getOrDefault(entity.getType(), "[MASKED]");
                    maskedMap = deepMaskMap(maskedMap, entity, mask);
                }
                result.add(maskedMap);
            } else if (item instanceof List) {
                // 递归处理嵌套List
                result.add(deepMaskList((List<?>) item, entities));
            } else {
                // 其他类型直接保留
                result.add(item);
            }
        }

        return result;
    }

    // 日志记录方法
    private void log(String message, Object... args) {
        log.info("[MaskDesensitizationStrategy] " + message, args);
    }
}
