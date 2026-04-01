package com.hdu.apisensitivities.service.Desensitization;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;

@Slf4j
@Component
public class DataReplacementDesensitizationStrategy implements DesensitizationStrategy {

    // 预定义的虚假数据池
    private final List<String> fakeNames = Arrays.asList(
            "张伟", "王芳", "李娜", "刘强", "陈杰", "杨丽", "黄勇", "周敏", "吴涛", "赵霞",
            "徐磊", "孙莉", "胡军", "朱婷", "高飞", "林芳", "何东", "梁艳", "宋超", "罗琴"
    );
    
    private final List<String> fakeEmailDomains = Arrays.asList(
            "example.com", "test.com", "demo.org", "sample.net", "fake.mail.com"
    );
    
    private final List<String> fakeAddresses = Arrays.asList(
            "北京市朝阳区某某街道123号", "上海市浦东新区某某路456号", "广州市天河区某某大道789号",
            "深圳市南山区某某社区101号", "杭州市西湖区某某小区202号", "南京市鼓楼区某某广场303号"
    );
    
    private final Random random = new Random();

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
                String replacement = generateReplacement(entity.getOriginalText(), entity.getType());
                // 确保索引不越界
                int start = Math.max(0, entity.getStart());
                int end = Math.min(text.length(), entity.getEnd());
                if (start <= end) {
                    result = result.substring(0, start) +
                            "[" + replacement + "]" +
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
            String replacement = generateReplacement(entity.getOriginalText(), entity.getType());
            dataMap = replaceFieldInMap(dataMap, fieldPath, replacement);
        }

        // 按值进行深度遍历处理
        for (SensitiveEntity entity : nonPathEntities) {
            String replacement = generateReplacement(entity.getOriginalText(), entity.getType());
            dataMap = deepReplaceMap(dataMap, entity, replacement);
        }

        return dataMap;
    }

    @Override
    public byte[] desensitizeBinaryData(byte[] binaryData, String dataType, List<SensitiveEntity> sensitiveEntities) {
        // 二进制数据脱敏，这里记录请求但返回原数据
        // 实际应用中可能需要根据二进制数据类型进行特定处理
        log.info("[DataReplacementDesensitizationStrategy] 接收到二进制数据[{}]脱敏请求，当前策略不支持直接脱敏", dataType);
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
        return "dataReplacementDesensitizationStrategy";
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
                SensitiveType.BIRTH_DATE,
                SensitiveType.CUSTOM
        ));
    }

    /**
     * 根据敏感类型生成替换数据
     * @param originalValue 原始值
     * @param type 敏感类型
     * @return 替换后的值
     */
    private String generateReplacement(String originalValue, SensitiveType type) {
        switch (type) {
            case PHONE_NUMBER:
                return generateFakePhoneNumber(originalValue);
            case BANK_CARD:
                return generateFakeBankCard(originalValue);
            case CREDIT_CARD:
                return generateFakeCreditCard(originalValue);
            case EMAIL:
                return generateFakeEmail(originalValue);
            case ID_CARD:
                return generateFakeIdCard(originalValue);
            case NAME:
                return generateFakeName(originalValue);
            case ADDRESS:
                return generateFakeAddress(originalValue);
            case BIRTH_DATE:
                return generateFakeBirthDate(originalValue);
            default:
                return generateFakeCustom(originalValue);
        }
    }

    /**
     * 生成虚假手机号码
     * @param original 原始手机号码
     * @return 格式相同但内容虚假的手机号码
     */
    private String generateFakePhoneNumber(String original) {
        // 保持格式相同，但生成虚假号码
        if (original.matches("1[3-9]\\d{9}")) {
            // 中国大陆手机号码格式
            int prefix = 130 + random.nextInt(70); // 130-199
            int middle = 1000 + random.nextInt(9000); // 1000-9999
            int suffix = 1000 + random.nextInt(9000); // 1000-9999
            return String.format("%d%04d%04d", prefix, middle, suffix);
        } else {
            // 其他格式，保持数字位数相同
            StringBuilder fakePhone = new StringBuilder();
            for (int i = 0; i < original.length(); i++) {
                if (Character.isDigit(original.charAt(i))) {
                    fakePhone.append(random.nextInt(10));
                } else {
                    fakePhone.append(original.charAt(i));
                }
            }
            return fakePhone.toString();
        }
    }

    /**
     * 生成虚假银行卡号
     * @param original 原始银行卡号
     * @return 格式相同但内容虚假的银行卡号
     */
    private String generateFakeBankCard(String original) {
        // 清理非数字字符
        String cleanCard = original.replaceAll("[^0-9]", "");
        if (cleanCard.length() >= 16) {
            // 生成16位银行卡号，保持前6位和后4位符合规则
            StringBuilder fakeCard = new StringBuilder();
            fakeCard.append(cleanCard.substring(0, 6)); // 保持BIN码不变（实际应用中也可以替换）
            
            // 生成中间位数
            for (int i = 6; i < cleanCard.length() - 4; i++) {
                fakeCard.append(random.nextInt(10));
            }
            
            // 保持最后4位
            fakeCard.append(cleanCard.substring(cleanCard.length() - 4));
            
            // 添加校验位（简化处理，实际银行卡有复杂的校验算法）
            return fakeCard.toString();
        } else {
            // 生成指定长度的随机数字
            StringBuilder fakeCard = new StringBuilder();
            for (int i = 0; i < cleanCard.length(); i++) {
                fakeCard.append(random.nextInt(10));
            }
            return fakeCard.toString();
        }
    }

    /**
     * 生成虚假信用卡号
     * @param original 原始信用卡号
     * @return 格式相同但内容虚假的信用卡号
     */
    private String generateFakeCreditCard(String original) {
        // 信用卡和银行卡处理方式类似
        return generateFakeBankCard(original);
    }

    /**
     * 生成虚假邮箱地址
     * @param original 原始邮箱地址
     * @return 格式相同但内容虚假的邮箱地址
     */
    private String generateFakeEmail(String original) {
        if (original.contains("@")) {
            String[] parts = original.split("@");
            String username = parts[0];
            // 生成虚假用户名
            StringBuilder fakeUsername = new StringBuilder();
            for (int i = 0; i < username.length(); i++) {
                if (Character.isLetter(username.charAt(i))) {
                    fakeUsername.append((char) ('a' + random.nextInt(26)));
                } else if (Character.isDigit(username.charAt(i))) {
                    fakeUsername.append(random.nextInt(10));
                } else {
                    fakeUsername.append(username.charAt(i));
                }
            }
            
            // 选择一个虚假域名
            String fakeDomain = fakeEmailDomains.get(random.nextInt(fakeEmailDomains.size()));
            
            return fakeUsername.toString() + "@" + fakeDomain;
        } else {
            // 不是标准邮箱格式，生成一个简单的虚假邮箱
            String fakeUsername = "user" + (10000 + random.nextInt(90000));
            String fakeDomain = fakeEmailDomains.get(random.nextInt(fakeEmailDomains.size()));
            return fakeUsername + "@" + fakeDomain;
        }
    }

    /**
     * 生成虚假身份证号
     * @param original 原始身份证号
     * @return 格式相同但内容虚假的身份证号
     */
    private String generateFakeIdCard(String original) {
        if (original.length() == 18) {
            // 18位身份证号
            String areaCode = "110101"; // 使用固定的地区代码
            String birthDate = String.format("%04d%02d%02d", 
                    1980 + random.nextInt(30), // 1980-2009年
                    1 + random.nextInt(12),    // 1-12月
                    1 + random.nextInt(28));   // 1-28日
            
            StringBuilder fakeId = new StringBuilder();
            fakeId.append(areaCode);
            fakeId.append(birthDate);
            
            // 生成顺序码
            for (int i = 0; i < 3; i++) {
                fakeId.append(random.nextInt(10));
            }
            
            // 简化的校验码（实际身份证有复杂的校验算法）
            fakeId.append(random.nextInt(10));
            
            return fakeId.toString();
        } else if (original.length() == 15) {
            // 15位身份证号（已停用）
            String areaCode = "110101";
            String birthDate = String.format("%02d%02d%02d", 
                    random.nextInt(30),        // 00-29年
                    1 + random.nextInt(12),    // 1-12月
                    1 + random.nextInt(28));   // 1-28日
            
            StringBuilder fakeId = new StringBuilder();
            fakeId.append(areaCode);
            fakeId.append(birthDate);
            
            // 生成顺序码
            for (int i = 0; i < 3; i++) {
                fakeId.append(random.nextInt(10));
            }
            
            return fakeId.toString();
        } else {
            // 其他情况，生成18位假身份证号
            return generateFakeIdCard("110101199001011234");
        }
    }

    /**
     * 生成虚假姓名
     * @param original 原始姓名
     * @return 虚假姓名
     */
    private String generateFakeName(String original) {
        // 从预定义列表中随机选择一个姓名
        return fakeNames.get(random.nextInt(fakeNames.size()));
    }

    /**
     * 生成虚假地址
     * @param original 原始地址
     * @return 虚假地址
     */
    private String generateFakeAddress(String original) {
        // 从预定义列表中随机选择一个地址
        return fakeAddresses.get(random.nextInt(fakeAddresses.size()));
    }

    /**
     * 生成虚假出生日期
     * @param original 原始出生日期
     * @return 虚假出生日期
     */
    private String generateFakeBirthDate(String original) {
        // 生成一个合理的出生日期（18-65岁之间）
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 18 - random.nextInt(47); // 18-65岁
        int birthMonth = 1 + random.nextInt(12);
        int birthDay = 1 + random.nextInt(28); // 简化处理，避免月份天数问题
        
        // 保持原始格式
        if (original.contains("-")) {
            return String.format("%d-%02d-%02d", birthYear, birthMonth, birthDay);
        } else if (original.contains("/")) {
            return String.format("%d/%02d/%02d", birthYear, birthMonth, birthDay);
        } else {
            return String.format("%d%02d%02d", birthYear, birthMonth, birthDay);
        }
    }

    /**
     * 生成虚假自定义数据
     * @param original 原始数据
     * @return 虚假数据
     */
    private String generateFakeCustom(String original) {
        // 对于自定义类型，生成相同长度的随机字符串
        StringBuilder fakeData = new StringBuilder();
        for (int i = 0; i < original.length(); i++) {
            if (Character.isLetter(original.charAt(i))) {
                fakeData.append((char) ('a' + random.nextInt(26)));
            } else if (Character.isDigit(original.charAt(i))) {
                fakeData.append(random.nextInt(10));
            } else {
                fakeData.append(original.charAt(i));
            }
        }
        return fakeData.toString();
    }

    // 按字段路径对Map中的字段进行替换处理
    private Map<String, Object> replaceFieldInMap(Map<String, Object> map, String fieldPath, String replacement) {
        if (map == null || fieldPath == null || fieldPath.isEmpty()) {
            return map;
        }

        String[] parts = fieldPath.split("\\.");
        return processReplaceFieldPath(map, parts, 0, replacement);
    }

    // 递归处理字段路径替换
    private Map<String, Object> processReplaceFieldPath(Map<String, Object> map, String[] pathParts, int index, String replacement) {
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
                        ((List<Object>) value).set(arrayIndex, "[" + replacement + "]");
                    } else if (listElement instanceof Map) {
                        // 嵌套对象，继续递归
                        processReplaceFieldPath((Map<String, Object>) listElement, pathParts, index + 1, replacement);
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
                processReplaceFieldPath((Map<String, Object>) value, pathParts, index + 1, replacement);
            } else if (value instanceof List) {
                // 列表，需要递归处理每个元素
                for (Object element : (List<?>) value) {
                    if (element instanceof Map) {
                        processReplaceFieldPath((Map<String, Object>) element, pathParts, index + 1, replacement);
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

    // 深度遍历Map进行替换
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
                result.put(key, deepReplaceMap((Map<String, Object>) value, entity, replacement));
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

    // 深度遍历List进行替换
    private List<Object> deepReplaceList(List<?> list, SensitiveEntity entity, String replacement) {
        List<Object> result = new ArrayList<>();

        for (Object item : list) {
            if (item instanceof String) {
                String strItem = (String) item;
                // 检查是否包含敏感信息
                if (strItem.contains(entity.getOriginalText())) {
                    strItem = strItem.replace(entity.getOriginalText(), "[" + replacement + "]");
                }
                result.add(strItem);
            } else if (item instanceof Map) {
                // 递归处理嵌套Map
                result.add(deepReplaceMap((Map<String, Object>) item, entity, replacement));
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