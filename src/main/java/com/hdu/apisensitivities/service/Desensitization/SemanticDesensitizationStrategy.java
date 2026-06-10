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
public class SemanticDesensitizationStrategy implements DesensitizationStrategy {

    @Autowired
    private GlobalSessionContextRepository contextRepository;

    private final Map<SensitiveType, List<String>> SEMANTIC_REPLACEMENTS = new HashMap<>();

    public SemanticDesensitizationStrategy() {
        // 初始化语义替换词库
        SEMANTIC_REPLACEMENTS.put(SensitiveType.PHONE_NUMBER, Arrays.asList(
                "联系电话", "电话号码", "手机号", "联络方式"));
        SEMANTIC_REPLACEMENTS.put(SensitiveType.BANK_CARD, Arrays.asList(
                "银行卡", "储蓄卡", "账户", "卡号"));
        SEMANTIC_REPLACEMENTS.put(SensitiveType.EMAIL, Arrays.asList(
                "邮箱地址", "电子邮箱", "邮件地址", "联系邮箱"));
        SEMANTIC_REPLACEMENTS.put(SensitiveType.ID_CARD, Arrays.asList(
                "身份证", "证件号", "身份信息", "个人证件"));
        SEMANTIC_REPLACEMENTS.put(SensitiveType.NAME, Arrays.asList(
                "用户", "人员", "客户", "姓名"));
        SEMANTIC_REPLACEMENTS.put(SensitiveType.ADDRESS, Arrays.asList(
                "地址信息", "居住地", "所在地", "位置"));
        SEMANTIC_REPLACEMENTS.put(SensitiveType.CREDIT_CARD, Arrays.asList(
                "信用卡", "贷记卡", "支付卡", "卡片"));
        SEMANTIC_REPLACEMENTS.put(SensitiveType.PASSWORD, Arrays.asList(
                "密码信息", "口令", "访问凭证", "安全码"));
        SEMANTIC_REPLACEMENTS.put(SensitiveType.BIRTH_DATE, Arrays.asList(
                "生日", "出生日期", "诞辰", "出生时间"));
        SEMANTIC_REPLACEMENTS.put(SensitiveType.CUSTOM, Arrays.asList(
                "敏感信息", "隐私数据", "保密信息", "个人信息"));
        SEMANTIC_REPLACEMENTS.put(SensitiveType.IP_ADDRESS, Arrays.asList(
                "IP地址", "网络地址", "主机地址", "节点地址"));
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
        String sessionId = DesensitizeRequestContext.getSessionId();
        for (SensitiveEntity entity : validEntities) {
            try {
                String originalText = entity.getOriginalText();
                String typeStr = entity.getType().name();
                SensitiveType type = entity.getType();

                String replacement = contextRepository.getOrCreateConsistencyValue(sessionId, originalText, typeStr,
                        currentId -> {
                            List<String> pool = SEMANTIC_REPLACEMENTS.getOrDefault(type,
                                    Arrays.asList("敏感信息", "隐私数据"));
                            // 用 originalText.hashCode() 替代 Random，同实体→同词
                            int idx = Math.abs(originalText.hashCode()) % pool.size();
                            return "[" + pool.get(idx) + "_" + currentId + "]";
                        });

                int start = Math.max(0, entity.getStart());
                int end = Math.min(text.length(), entity.getEnd());
                if (start <= end) {
                    result = result.substring(0, start) + replacement + result.substring(end);
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

        Map<String, Object> dataMap = new HashMap<>(structuredData);

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
            String replacement = pickReplacement(entity);
            dataMap = replaceFieldInMap(dataMap, entity, replacement);
        }

        // 按值进行深度遍历处理
        for (SensitiveEntity entity : nonPathEntities) {
            String replacement = pickReplacement(entity);
            dataMap = deepReplaceMap(dataMap, entity, replacement);
        }

        return dataMap;
    }

    @Override
    public byte[] desensitizeBinaryData(byte[] binaryData, String dataType, List<SensitiveEntity> sensitiveEntities) {
        // 二进制数据脱敏，这里记录请求但返回原数据
        // 实际应用中可能需要根据二进制数据类型进行特定处理
        log.info("[SemanticDesensitizationStrategy] 接收到二进制数据[{}]脱敏请求，当前策略不支持直接脱敏", dataType);
        return binaryData;
    }

    @Override
    public Set<String> supportedDataTypes() {
        // 支持文本和结构化数据类型
        return new HashSet<>(Arrays.asList("TEXT", "JSON", "XML"));
    }

    @Override
    public boolean supportsDataType(String dataType) {
        return supportedDataTypes().contains(dataType.toUpperCase());
    }

    @Override
    public String getName() {
        return "semanticDesensitizationStrategy";
    }

    @Override
    public Set<SensitiveType> supportedTypes() {
        return new HashSet<>(Arrays.asList(
                SensitiveType.PHONE_NUMBER,
                SensitiveType.BANK_CARD,
                SensitiveType.EMAIL,
                SensitiveType.ID_CARD,
                SensitiveType.NAME,
                SensitiveType.ADDRESS,
                SensitiveType.CREDIT_CARD,
                SensitiveType.PASSWORD,
                SensitiveType.BIRTH_DATE,
                SensitiveType.CUSTOM,
                SensitiveType.IP_ADDRESS));
    }

    /**
     * 为结构化数据路径使用的确定性选词。
     * 基于 originalText.hashCode() 从词库中固定选取，同实体→同词。
     */
    private String pickReplacement(SensitiveEntity entity) {
        List<String> pool = SEMANTIC_REPLACEMENTS.getOrDefault(entity.getType(),
                Arrays.asList("敏感信息", "隐私数据"));
        String text = entity.getOriginalText() != null ? entity.getOriginalText() : "";
        int idx = Math.abs(text.hashCode()) % pool.size();
        return pool.get(idx);
    }

    // 按字段路径对Map中的字段进行替换处理
    private Map<String, Object> replaceFieldInMap(Map<String, Object> map, SensitiveEntity entity, String replacement) {
        if (map == null) {
            return map;
        }

        String fieldPath = (String) entity.getMetadata().get("fieldPath");
        if (fieldPath == null || fieldPath.isEmpty()) {
            return map;
        }

        String[] parts = fieldPath.split("\\.");
        return processReplaceFieldPath(map, parts, 0, replacement);
    }

    // 递归处理字段路径替换
    private Map<String, Object> processReplaceFieldPath(Map<String, Object> map, String[] pathParts, int index,
            String replacement) {
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
                        // 最后一部分且是字符串，进行替换
                        List<Object> listValue = CollectionTypeUtils.asObjectList(value);
                        if (listValue != null) {
                            listValue.set(arrayIndex, "[" + replacement + "]");
                        }
                    } else if (listElement instanceof Map) {
                        // 嵌套对象，继续递归
                        Map<String, Object> nestedMap = CollectionTypeUtils.asStringObjectMap(listElement);
                        if (nestedMap != null) {
                            processReplaceFieldPath(nestedMap, pathParts, index + 1, replacement);
                        }
                    }
                }
            }
        } else if (map.containsKey(part)) {
            Object value = map.get(part);
            if (index == pathParts.length - 1 && value instanceof String) {
                // 最后一部分且是字符串，进行替换
                map.put(part, "[" + replacement + "]");
            } else if (value instanceof Map) {
                // 嵌套对象，继续递归
                Map<String, Object> nestedMap = CollectionTypeUtils.asStringObjectMap(value);
                if (nestedMap != null) {
                    processReplaceFieldPath(nestedMap, pathParts, index + 1, replacement);
                }
            } else if (value instanceof List) {
                // 列表，需要递归处理每个元素
                for (Object element : (List<?>) value) {
                    if (element instanceof Map) {
                        Map<String, Object> nestedMap = CollectionTypeUtils.asStringObjectMap(element);
                        if (nestedMap != null) {
                            processReplaceFieldPath(nestedMap, pathParts, index + 1, replacement);
                        }
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

    // 深度遍历Map进行语义替换
    private Map<String, Object> deepReplaceMap(Map<String, Object> map, SensitiveEntity entity, String replacement) {
        Map<String, Object> result = new HashMap<>();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof String) {
                String strValue = (String) value;
                // 检查是否包含敏感信息
                if (strValue.contains(entity.getOriginalText())) {
                    result.put(key, strValue.replace(entity.getOriginalText(), "[" + replacement + "]"));
                } else {
                    result.put(key, strValue);
                }
            } else if (value instanceof Map) {
                // 递归处理嵌套Map
                Map<String, Object> nestedMap = CollectionTypeUtils.asStringObjectMap(value);
                result.put(key, nestedMap == null ? value : deepReplaceMap(nestedMap, entity, replacement));
            } else if (value instanceof List) {
                // 处理List
                result.put(key, deepReplaceList((List<?>) value, entity, replacement));
            } else {
                // 其他类型直接保留
                result.put(key, value);
            }
        }

        return result;
    }

    // 深度遍历List进行语义替换
    private List<Object> deepReplaceList(List<?> list, SensitiveEntity entity, String replacement) {
        List<Object> result = new ArrayList<>();

        for (Object item : list) {
            if (item instanceof String) {
                String strItem = (String) item;
                // 检查是否包含敏感信息
                if (strItem.contains(entity.getOriginalText())) {
                    strItem = strItem.replace(entity.getOriginalText(), replacement);
                }
                result.add(strItem);
            } else if (item instanceof Map) {
                // 递归处理嵌套Map
                Map<String, Object> nestedMap = CollectionTypeUtils.asStringObjectMap(item);
                result.add(nestedMap == null ? item : deepReplaceMap(nestedMap, entity, replacement));
            } else if (item instanceof List) {
                // 递归处理嵌套List
                result.add(deepReplaceList((List<?>) item, entity, replacement));
            } else {
                // 其他类型直接保留
                result.add(item);
            }
        }

        return result;
    }
}
