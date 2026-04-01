package com.hdu.apisensitivities.service.Desensitization;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Slf4j
@Component
public class GeneralizationDesensitizationStrategy implements DesensitizationStrategy {

    private final Map<SensitiveType, String> GENERALIZATION_TEMPLATES = new HashMap<>();

    public GeneralizationDesensitizationStrategy() {
        // 初始化泛化模板
        GENERALIZATION_TEMPLATES.put(SensitiveType.PHONE_NUMBER, "手机号码");
        GENERALIZATION_TEMPLATES.put(SensitiveType.BANK_CARD, "银行卡号");
        GENERALIZATION_TEMPLATES.put(SensitiveType.EMAIL, "电子邮箱");
        GENERALIZATION_TEMPLATES.put(SensitiveType.ID_CARD, "身份证号");
        GENERALIZATION_TEMPLATES.put(SensitiveType.NAME, "姓名");
        GENERALIZATION_TEMPLATES.put(SensitiveType.ADDRESS, "地址");
        GENERALIZATION_TEMPLATES.put(SensitiveType.CREDIT_CARD, "信用卡号");
        GENERALIZATION_TEMPLATES.put(SensitiveType.PASSWORD, "密码");
        GENERALIZATION_TEMPLATES.put(SensitiveType.BIRTH_DATE, "出生日期");
        GENERALIZATION_TEMPLATES.put(SensitiveType.CUSTOM, "自定义敏感信息");
        GENERALIZATION_TEMPLATES.put(SensitiveType.IP_ADDRESS, "IP地址");
    }

    @Override
    public String desensitize(String text, List<SensitiveEntity> sensitiveEntities) {
        if (sensitiveEntities.isEmpty()) {
            return text;
        }

        // 过滤掉没有正确位置信息的实体（例如来自结构化数据的实体）
        List<SensitiveEntity> validEntities = sensitiveEntities.stream()
                .filter(entity -> entity.getStart() >= 0 && entity.getEnd() <= text.length() && entity.getStart() <= entity.getEnd())
                .sorted((e1, e2) -> Integer.compare(e2.getStart(), e1.getStart()))
                .collect(Collectors.toList());

        if (validEntities.isEmpty()) {
            return text;
        }

        String result = text;
        for (SensitiveEntity entity : validEntities) {
            try {
                String generalization = generalizeValue(entity.getOriginalText(), entity.getType());
                // 确保索引不越界
                int start = Math.max(0, entity.getStart());
                int end = Math.min(text.length(), entity.getEnd());
                if (start <= end) {
                    result = result.substring(0, start) +
                            "[" + generalization + "]" +
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
    public Map<String, Object> desensitizeStructuredData(Map<String, Object> structuredData, List<SensitiveEntity> sensitiveEntities) {
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
            String fieldPath = (String) entity.getMetadata().get("fieldPath");
            String generalization = generalizeValue(entity.getOriginalText(), entity.getType());
            dataMap = generalizeFieldInMap(dataMap, fieldPath, generalization);
        }

        // 按值进行深度遍历处理
        for (SensitiveEntity entity : nonPathEntities) {
            String generalization = generalizeValue(entity.getOriginalText(), entity.getType());
            dataMap = deepGeneralizeMap(dataMap, entity, generalization);
        }

        return dataMap;
    }

    @Override
    public byte[] desensitizeBinaryData(byte[] binaryData, String dataType, List<SensitiveEntity> sensitiveEntities) {
        // 二进制数据脱敏，这里记录请求但返回原数据
        // 实际应用中可能需要根据二进制数据类型进行特定处理
        log.info("[GeneralizationDesensitizationStrategy] 接收到二进制数据[{}]脱敏请求，当前策略不支持直接脱敏", dataType);
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
        return "generalizationDesensitizationStrategy";
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
                SensitiveType.IP_ADDRESS
        ));
    }

    /**
     * 根据敏感类型对值进行泛化处理
     * @param originalValue 原始值
     * @param type 敏感类型
     * @return 泛化后的值
     */
    private String generalizeValue(String originalValue, SensitiveType type) {
        switch (type) {
            case PHONE_NUMBER:
                return generalizePhoneNumber(originalValue);
            case BANK_CARD:
            case CREDIT_CARD:
                return generalizeCardNumber(originalValue);
            case ID_CARD:
                return generalizeIdCard(originalValue);
            case BIRTH_DATE:
                return generalizeBirthDate(originalValue);
            case ADDRESS:
                return generalizeAddress(originalValue);
            case NAME:
                return generalizeName(originalValue);
            case IP_ADDRESS:
                return originalValue != null && originalValue.contains(":") ? "IPv6地址" : "IP地址";
            default:
                return GENERALIZATION_TEMPLATES.getOrDefault(type, "敏感信息");
        }
    }

    /**
     * 泛化手机号码 - 保留号段信息
     * @param phone 手机号码
     * @return 泛化后的手机号码范围
     */
    private String generalizePhoneNumber(String phone) {
        // 清理非数字字符
        String cleanPhone = phone.replaceAll("[^0-9]", "");
        
        if (cleanPhone.length() >= 11) {
            // 中国大陆手机号码，保留前3位和后4位
            String prefix = cleanPhone.substring(0, 3);
            String suffix = cleanPhone.substring(7);
            return prefix + "****" + suffix + "号段";
        } else if (cleanPhone.length() >= 7) {
            // 其他情况，保留前3位和后2位
            String prefix = cleanPhone.substring(0, 3);
            String suffix = cleanPhone.substring(cleanPhone.length() - 2);
            return prefix + "***" + suffix + "号段";
        } else {
            return "手机号码";
        }
    }

    /**
     * 泛化银行卡号 - 保留发卡行信息和卡类型
     * @param cardNumber 银行卡号
     * @return 泛化后的银行卡号范围
     */
    private String generalizeCardNumber(String cardNumber) {
        // 清理非数字字符
        String cleanCard = cardNumber.replaceAll("[^0-9]", "");
        
        if (cleanCard.length() >= 6) {
            // 保留前6位（发卡行识别码）和后4位
            String prefix = cleanCard.substring(0, 6);
            String suffix = cleanCard.substring(cleanCard.length() - 4);
            return prefix + "****" + suffix + "卡号段";
        } else {
            return "银行卡号";
        }
    }

    /**
     * 泛化身份证号 - 保留地区和年龄范围信息
     * @param idCard 身份证号
     * @return 泛化后的身份证号范围
     */
    private String generalizeIdCard(String idCard) {
        if (idCard.length() == 18) {
            // 18位身份证号
            String areaCode = idCard.substring(0, 6);
            String birthDate = idCard.substring(6, 14);
            
            try {
                // 解析出生日期计算年龄范围
                LocalDate birth = LocalDate.parse(birthDate, DateTimeFormatter.ofPattern("yyyyMMdd"));
                int age = LocalDate.now().getYear() - birth.getYear();
                int ageRangeStart = (age / 10) * 10; // 年龄段起始
                int ageRangeEnd = ageRangeStart + 9; // 年龄段结束
                
                return areaCode + "地区" + ageRangeStart + "-" + ageRangeEnd + "岁人群";
            } catch (DateTimeParseException e) {
                return "某地区中青年";
            }
        } else if (idCard.length() == 15) {
            // 15位身份证号（已停用）
            String areaCode = idCard.substring(0, 6);
            return areaCode + "地区中老年";
        } else {
            return "身份证号";
        }
    }

    /**
     * 泛化出生日期 - 保留年龄范围
     * @param birthDate 出生日期
     * @return 泛化后的年龄范围
     */
    private String generalizeBirthDate(String birthDate) {
        try {
            // 尝试多种日期格式解析
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
            int ageRangeStart = (age / 10) * 10; // 年龄段起始
            int ageRangeEnd = ageRangeStart + 9; // 年龄段结束
            
            return ageRangeStart + "-" + ageRangeEnd + "岁";
        } catch (DateTimeParseException e) {
            return "出生日期";
        }
    }

    /**
     * 泛化地址 - 保留大致地理位置
     * @param address 地址
     * @return 泛化后的地址范围
     */
    private String generalizeAddress(String address) {
        // 简单处理，保留省级或市级信息
        if (address.contains("省")) {
            int index = address.indexOf("省");
            return address.substring(0, index + 1) + "地区";
        } else if (address.contains("市")) {
            int index = address.indexOf("市");
            return address.substring(0, index + 1) + "地区";
        } else {
            return "某地区";
        }
    }

    /**
     * 泛化姓名 - 保留姓氏信息
     * @param name 姓名
     * @return 泛化后的姓名范围
     */
    private String generalizeName(String name) {
        if (name.length() > 1) {
            return name.charAt(0) + "姓氏人群";
        } else {
            return "某姓氏人群";
        }
    }

    // 按字段路径对Map中的字段进行泛化处理
    private Map<String, Object> generalizeFieldInMap(Map<String, Object> map, String fieldPath, String generalization) {
        if (map == null || fieldPath == null || fieldPath.isEmpty()) {
            return map;
        }

        String[] parts = fieldPath.split("\\.");
        return processFieldPath(map, parts, 0, generalization);
    }

    // 递归处理字段路径
    private Map<String, Object> processFieldPath(Map<String, Object> map, String[] pathParts, int index, String generalization) {
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
                        ((List<Object>) value).set(arrayIndex, "[" + generalization + "]");
                    } else if (listElement instanceof Map) {
                        // 嵌套对象，继续递归
                        processFieldPath((Map<String, Object>) listElement, pathParts, index + 1, generalization);
                    }
                }
            }
        } else if (map.containsKey(part)) {
            Object value = map.get(part);
            if (index == pathParts.length - 1 && value instanceof String) {
                // 最后一部分且是字符串，进行脱敏
                map.put(part, "[" + generalization + "]");
            } else if (value instanceof Map) {
                // 嵌套对象，继续递归
                processFieldPath((Map<String, Object>) value, pathParts, index + 1, generalization);
            } else if (value instanceof List) {
                // 列表，需要递归处理每个元素
                for (Object element : (List<?>) value) {
                    if (element instanceof Map) {
                        processFieldPath((Map<String, Object>) element, pathParts, index + 1, generalization);
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

    // 深度遍历Map进行泛化替换
    private Map<String, Object> deepGeneralizeMap(Map<String, Object> map, SensitiveEntity entity, String generalization) {
        Map<String, Object> result = new HashMap<>();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof String) {
                String strValue = (String) value;
                // 检查是否包含敏感信息
                if (strValue.contains(entity.getOriginalText())) {
                    result.put(key, strValue.replace(entity.getOriginalText(), "[" + generalization + "]"));
                } else {
                    result.put(key, strValue);
                }
            } else if (value instanceof Map) {
                // 递归处理嵌套Map
                result.put(key, deepGeneralizeMap((Map<String, Object>) value, entity, generalization));
            } else if (value instanceof List) {
                // 处理List
                result.put(key, deepGeneralizeList((List<?>) value, entity, generalization));
            } else {
                // 其他类型直接保留
                result.put(key, value);
            }
        }

        return result;
    }

    // 深度遍历List进行泛化替换
    private List<Object> deepGeneralizeList(List<?> list, SensitiveEntity entity, String generalization) {
        List<Object> result = new ArrayList<>();

        for (Object item : list) {
            if (item instanceof String) {
                String strItem = (String) item;
                // 检查是否包含敏感信息
                if (strItem.contains(entity.getOriginalText())) {
                    strItem = strItem.replace(entity.getOriginalText(), "[" + generalization + "]");
                }
                result.add(strItem);
            } else if (item instanceof Map) {
                // 递归处理嵌套Map
                result.add(deepGeneralizeMap((Map<String, Object>) item, entity, generalization));
            } else if (item instanceof List) {
                // 递归处理嵌套List
                result.add(deepGeneralizeList((List<?>) item, entity, generalization));
            } else {
                // 其他类型直接保留
                result.add(item);
            }
        }

        return result;
    }
}
