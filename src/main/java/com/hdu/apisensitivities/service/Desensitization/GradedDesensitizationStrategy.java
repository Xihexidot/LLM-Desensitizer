package com.hdu.apisensitivities.service.Desensitization;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveLevel;
import com.hdu.apisensitivities.entity.SensitiveType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分级分类脱敏策略：依据 {@link SensitiveDataClassifier} 的级别判定，
 * 对高/中/低敏信息分别执行完全掩码 / 部分脱敏 / 泛化处理。
 * <p>
 * 分级语义：
 * <ul>
 * <li>{@link SensitiveLevel#HIGH} → 完全掩码 {@code [TYPE_N]}（如 {@code [ID_CARD_1]}），零信息残留</li>
 * <li>{@link SensitiveLevel#MEDIUM} → 部分脱敏（如 {@code [138****1234_1]}），保留可辨识片段</li>
 * <li>{@link SensitiveLevel#LOW} → 泛化处理（如 {@code [138****1234号段_1]}），保留统计价值</li>
 * </ul>
 * 同一会话内同一明文始终映射到同一占位符（会话一致性），并支持按 {@code desensitization.rule.type-levels} 动态调整类型级别。
 * </p>
 */
@Slf4j
@Component
public class GradedDesensitizationStrategy implements DesensitizationStrategy {

    @Autowired(required = false)
    private GlobalSessionContextRepository contextRepository;

    private final SensitiveDataClassifier classifier;

    private static final Map<SensitiveType, String> MASK_TEMPLATES = new EnumMap<>(SensitiveType.class);

    static {
        MASK_TEMPLATES.put(SensitiveType.PHONE_NUMBER, "PHONE");
        MASK_TEMPLATES.put(SensitiveType.BANK_CARD, "BANK_CARD");
        MASK_TEMPLATES.put(SensitiveType.EMAIL, "EMAIL");
        MASK_TEMPLATES.put(SensitiveType.ID_CARD, "ID_CARD");
        MASK_TEMPLATES.put(SensitiveType.NAME, "NAME");
        MASK_TEMPLATES.put(SensitiveType.PERSON, "PERSON");
        MASK_TEMPLATES.put(SensitiveType.ADDRESS, "ADDRESS");
        MASK_TEMPLATES.put(SensitiveType.ORGANIZATION, "ORG");
        MASK_TEMPLATES.put(SensitiveType.CREDIT_CARD, "CREDIT_CARD");
        MASK_TEMPLATES.put(SensitiveType.PASSWORD, "PASSWORD");
        MASK_TEMPLATES.put(SensitiveType.API_KEY, "API_KEY");
        MASK_TEMPLATES.put(SensitiveType.PASSPORT, "PASSPORT");
        MASK_TEMPLATES.put(SensitiveType.BIRTH_DATE, "BIRTH_DATE");
        MASK_TEMPLATES.put(SensitiveType.CUSTOM, "CUSTOM");
        MASK_TEMPLATES.put(SensitiveType.IP_ADDRESS, "IP");
        MASK_TEMPLATES.put(SensitiveType.LICENSE_PLATE, "PLATE");
        MASK_TEMPLATES.put(SensitiveType.SOCIAL_SECURITY, "SOCIAL_SECURITY");
    }

    public GradedDesensitizationStrategy(SensitiveDataClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public String desensitize(String text, List<SensitiveEntity> sensitiveEntities) {
        if (text == null || sensitiveEntities == null || sensitiveEntities.isEmpty()) {
            return text;
        }

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
                SensitiveLevel level = classifier.classify(entity.getType());
                String originalText = entity.getOriginalText();
                String typeStr = entity.getType() == null ? "UNKNOWN" : entity.getType().name();
                String formatted = formatByLevel(level, originalText, entity.getType());

                String replacement;
                if (contextRepository != null) {
                    replacement = contextRepository.getOrCreateConsistencyValue(sessionId, originalText, typeStr,
                            currentId -> "[" + formatted + "_" + currentId + "]");
                } else {
                    replacement = "[" + formatted + "]";
                }

                int start = Math.max(0, entity.getStart());
                int end = Math.min(text.length(), entity.getEnd());
                if (start <= end) {
                    result = result.substring(0, start) + replacement + result.substring(end);
                }
            } catch (StringIndexOutOfBoundsException e) {
                log.warn("分级脱敏过程中出现索引越界，实体: {}, 文本长度: {}", entity, text.length());
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> desensitizeStructuredData(Map<String, Object> structuredData,
            List<SensitiveEntity> sensitiveEntities) {
        if (structuredData == null || sensitiveEntities == null || sensitiveEntities.isEmpty()) {
            return structuredData;
        }
        // 结构化数据基于值/字段路径替换，无索引偏移问题，可按级别分组链式处理
        Map<String, Object> result = new HashMap<>(structuredData);
        for (SensitiveEntity entity : sensitiveEntities) {
            SensitiveLevel level = classifier.classify(entity.getType());
            String formatted = formatByLevel(level, entity.getOriginalText(), entity.getType());
            String replacement = "[" + formatted + "]";
            if (entity.getMetadata() != null && entity.getMetadata().containsKey("fieldPath")) {
                String fieldPath = (String) entity.getMetadata().get("fieldPath");
                result = replaceByFieldPath(result, fieldPath, entity.getOriginalText(), replacement);
            } else {
                result = deepReplaceByValue(result, entity.getOriginalText(), replacement);
            }
        }
        return result;
    }

    @Override
    public byte[] desensitizeBinaryData(byte[] binaryData, String dataType, List<SensitiveEntity> sensitiveEntities) {
        log.info("[GradedDesensitizationStrategy] 接收到二进制数据[{}]脱敏请求，当前策略不支持直接脱敏", dataType);
        return binaryData;
    }

    @Override
    public Set<SensitiveType> supportedTypes() {
        return new HashSet<>(Arrays.asList(SensitiveType.values()));
    }

    @Override
    public Set<String> supportedDataTypes() {
        return new HashSet<>(Arrays.asList("TEXT", "JSON", "XML"));
    }

    @Override
    public boolean supportsDataType(String dataType) {
        return supportedDataTypes().contains(dataType.toUpperCase());
    }

    @Override
    public String getName() {
        return "gradedDesensitizationStrategy";
    }

    // ========== 分级格式化 ==========

    private String formatByLevel(SensitiveLevel level, String original, SensitiveType type) {
        if (level == null) {
            level = SensitiveLevel.LOW;
        }
        return switch (level) {
            case HIGH -> MASK_TEMPLATES.getOrDefault(type, "MASKED");
            case MEDIUM -> partialMask(original, type);
            case LOW -> generalize(original, type);
        };
    }

    // 中敏：部分脱敏
    private String partialMask(String original, SensitiveType type) {
        if (original == null || original.isEmpty()) {
            return "***";
        }
        switch (type) {
            case PHONE_NUMBER:
                String digits = original.replaceAll("[^0-9]", "");
                return digits.length() > 7
                        ? digits.substring(0, 3) + "****" + digits.substring(7)
                        : "****";
            case BANK_CARD:
            case CREDIT_CARD:
                return original.length() > 4 ? "****" + original.substring(original.length() - 4) : "****";
            case EMAIL:
                int atIndex = original.indexOf('@');
                return atIndex > 0 ? original.substring(0, 2) + "***" + original.substring(atIndex) : "***@***";
            case NAME:
            case PERSON:
                return original.length() > 1 ? original.charAt(0) + "**" : "*";
            case BIRTH_DATE:
                return generalizeBirthDate(original);
            case IP_ADDRESS:
                if (original.contains(".")) {
                    String[] parts = original.split("\\.");
                    return parts.length == 4 ? parts[0] + "." + parts[1] + ".*.*" : "*.*.*.*";
                }
                return "IP地址";
            default:
                return "***";
        }
    }

    // 低敏：泛化处理（保留统计价值）
    private String generalize(String original, SensitiveType type) {
        if (original == null || original.isEmpty()) {
            return "敏感信息";
        }
        switch (type) {
            case PHONE_NUMBER:
                String digits = original.replaceAll("[^0-9]", "");
                if (digits.length() >= 11) {
                    return digits.substring(0, 3) + "****" + digits.substring(7) + "号段";
                }
                return "手机号码";
            case BANK_CARD:
            case CREDIT_CARD:
                String cardDigits = original.replaceAll("[^0-9]", "");
                if (cardDigits.length() >= 6) {
                    return cardDigits.substring(0, 6) + "****" + cardDigits.substring(cardDigits.length() - 4) + "卡号段";
                }
                return "银行卡号";
            case ID_CARD:
                return generalizeIdCard(original);
            case NAME:
            case PERSON:
                return original.length() > 1 ? original.charAt(0) + "姓氏人群" : "某姓氏人群";
            case ADDRESS:
                if (original.contains("省")) {
                    return original.substring(0, original.indexOf("省") + 1) + "地区";
                } else if (original.contains("市")) {
                    return original.substring(0, original.indexOf("市") + 1) + "地区";
                }
                return "某地区";
            case ORGANIZATION:
                return "某机构";
            case IP_ADDRESS:
                return "IP地址";
            case LICENSE_PLATE:
                return "车牌号码";
            default:
                return "敏感信息";
        }
    }

    private String generalizeBirthDate(String birthDate) {
        try {
            LocalDate date;
            if (birthDate.contains("-")) {
                date = LocalDate.parse(birthDate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            } else if (birthDate.contains("/")) {
                date = LocalDate.parse(birthDate, DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            } else if (birthDate.length() == 8) {
                date = LocalDate.parse(birthDate, DateTimeFormatter.ofPattern("yyyyMMdd"));
            } else {
                return "出生日期";
            }
            int age = LocalDate.now().getYear() - date.getYear();
            int rangeStart = (age / 10) * 10;
            return rangeStart + "-" + (rangeStart + 9) + "岁";
        } catch (DateTimeParseException e) {
            return "出生日期";
        }
    }

    private String generalizeIdCard(String idCard) {
        if (idCard.length() == 18) {
            String areaCode = idCard.substring(0, 6);
            String birthDate = idCard.substring(6, 14);
            try {
                LocalDate birth = LocalDate.parse(birthDate, DateTimeFormatter.ofPattern("yyyyMMdd"));
                int age = LocalDate.now().getYear() - birth.getYear();
                int rangeStart = (age / 10) * 10;
                return areaCode + "地区" + rangeStart + "-" + (rangeStart + 9) + "岁人群";
            } catch (DateTimeParseException e) {
                return "某地区中青年";
            }
        } else if (idCard.length() == 15) {
            return idCard.substring(0, 6) + "地区中老年";
        }
        return "身份证号";
    }

    // ========== 结构化数据替换 ==========

    private Map<String, Object> replaceByFieldPath(Map<String, Object> map, String fieldPath, String original,
            String replacement) {
        if (map == null || fieldPath == null || fieldPath.isEmpty()) {
            return map;
        }
        String[] parts = fieldPath.split("\\.");
        return processFieldPath(map, parts, 0, original, replacement);
    }

    private Map<String, Object> processFieldPath(Map<String, Object> map, String[] parts, int index, String original,
            String replacement) {
        if (index >= parts.length || map == null) {
            return map;
        }
        String part = parts[index];
        if (map.containsKey(part)) {
            Object value = map.get(part);
            if (index == parts.length - 1 && value instanceof String) {
                map.put(part, ((String) value).replace(original, replacement));
            } else if (value instanceof Map) {
                Map<String, Object> nested = toMap(value);
                if (nested != null) {
                    processFieldPath(nested, parts, index + 1, original, replacement);
                }
            } else if (value instanceof List) {
                for (Object element : (List<?>) value) {
                    if (element instanceof Map) {
                        Map<String, Object> nested = toMap(element);
                        if (nested != null) {
                            processFieldPath(nested, parts, index + 1, original, replacement);
                        }
                    } else if (element instanceof String) {
                        ((List<Object>) value).set(((List<?>) value).indexOf(element),
                                ((String) element).replace(original, replacement));
                    }
                }
            }
        }
        return map;
    }

    private Map<String, Object> deepReplaceByValue(Map<String, Object> map, String original, String replacement) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                result.put(entry.getKey(), ((String) value).contains(original)
                        ? ((String) value).replace(original, replacement)
                        : value);
            } else if (value instanceof Map) {
                Map<String, Object> nested = toMap(value);
                result.put(entry.getKey(), nested == null ? value : deepReplaceByValue(nested, original, replacement));
            } else if (value instanceof List) {
                result.put(entry.getKey(), deepReplaceList((List<?>) value, original, replacement));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private List<Object> deepReplaceList(List<?> list, String original, String replacement) {
        List<Object> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String) {
                result.add(((String) item).contains(original) ? ((String) item).replace(original, replacement) : item);
            } else if (item instanceof Map) {
                Map<String, Object> nested = toMap(item);
                result.add(nested == null ? item : deepReplaceByValue(nested, original, replacement));
            } else if (item instanceof List) {
                result.add(deepReplaceList((List<?>) item, original, replacement));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }
}
