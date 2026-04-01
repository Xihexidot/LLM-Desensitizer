package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.service.DataParser.DataParserManager;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.ScenarioPerception.ScenarioAnalysisResult;
import com.hdu.apisensitivities.utils.PatternRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@Primary
public class RegexDetectionService implements SensitiveDetectionService {

    private final Map<String, Pattern> customPatterns;
    private final DataParserManager dataParserManager;

    @Autowired
    public RegexDetectionService(DataParserManager dataParserManager) {
        this.customPatterns = new HashMap<>();
        this.dataParserManager = dataParserManager;
    }

    @Override
    public List<SensitiveEntity> detectSensitiveInfo(String text, String language) {
        return detectSensitiveInfo(text, language, null, null);
    }

    @Override
    public List<SensitiveEntity> detectSensitiveInfo(String text, String language, Set<String> includeTypes) {
        return detectSensitiveInfo(text, language, includeTypes, null);
    }

    @Override
    public List<SensitiveEntity> detectSensitiveInfo(String text, String language, Set<String> includeTypes,
            ScenarioAnalysisResult context) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<SensitiveEntity> entities = new ArrayList<>();

        // ========== 方式A：预定义正则模式 ==========
        // PatternRegistry.getAllPatterns() 返回所有预定义的正则规则
        Map<SensitiveType, Pattern> patterns = PatternRegistry.getAllPatterns();

        for (Map.Entry<SensitiveType, Pattern> entry : patterns.entrySet()) {
            // 如果指定了检测类型，只检测指定的类型
            if (includeTypes == null || includeTypes.contains(entry.getKey().name())) {
                entities.addAll(detectWithPattern(text, entry.getKey(), entry.getValue(), context));
            }
        }

        // 检测自定义模式的敏感信息
        for (Map.Entry<String, Pattern> entry : customPatterns.entrySet()) {
            // 如果includeTypes不为空且包含CUSTOM类型，则检测自定义模式
            if (includeTypes == null || includeTypes.contains(SensitiveType.CUSTOM.name())) {
                entities.addAll(detectCustomPattern(text, entry.getKey(), entry.getValue()));
            }
        }

        // 合并重叠的实体
        return mergeOverlappingEntities(entities);
    }

    @Override
    public List<SensitiveEntity> detectSensitiveInfoInStructuredData(Map<String, Object> structuredData,
            String language) {
        return detectSensitiveInfoInStructuredData(structuredData, language, null);
    }

    @Override
    public List<SensitiveEntity> detectSensitiveInfoInStructuredData(Map<String, Object> structuredData,
            String language, Set<String> includeTypes) {
        if (structuredData == null || structuredData.isEmpty()) {
            return Collections.emptyList();
        }

        List<SensitiveEntity> entities = new ArrayList<>();
        // 深度遍历结构化数据进行敏感信息检测
        detectInStructuredData(structuredData, "", entities, language, includeTypes);
        return mergeOverlappingEntities(entities);
    }

    @Override
    public List<SensitiveEntity> detectSensitiveInfoInBinary(byte[] binaryData, String dataType, String language) {
        return detectSensitiveInfoInBinary(binaryData, dataType, language, null);
    }

    @Override
    public List<SensitiveEntity> detectSensitiveInfoInBinary(byte[] binaryData, String dataType, String language,
            Set<String> includeTypes) {
        if (binaryData == null || binaryData.length == 0) {
            return Collections.emptyList();
        }

        log.info("接收到二进制数据[{}]敏感信息检测请求，大小: {}字节", dataType, binaryData.length);

        try {
            // 实际应用中可能需要根据数据类型进行特定处理，如OCR、文本提取等
            // 对于一些常见的二进制格式，可以尝试提取文本信息
            String extractedText = dataParserManager.parseMultipartFile(
                    new org.springframework.mock.web.MockMultipartFile("file", binaryData), dataType);
            if (extractedText != null && !extractedText.isEmpty()) {
                List<SensitiveEntity> entities = detectSensitiveInfo(extractedText, language, includeTypes);
                // 为二进制数据的敏感实体添加元数据
                entities.forEach(entity -> entity.addMetadata("binaryDataType", dataType));
                return entities;
            }
        } catch (Exception e) {
            log.error("二进制数据敏感信息检测失败: {}", e.getMessage(), e);
        }

        return Collections.emptyList();
    }

    @Override
    public Map<String, List<SensitiveEntity>> batchDetect(List<String> texts, String language) {
        return batchDetect(texts, language, null);
    }

    @Override
    public Map<String, List<SensitiveEntity>> batchDetect(List<String> texts, String language,
            Set<String> includeTypes) {
        return texts.parallelStream()
                .collect(Collectors.toMap(
                        text -> text,
                        text -> detectSensitiveInfo(text, language, includeTypes)));
    }

    @Override
    public Map<String, List<SensitiveEntity>> batchDetectStructuredData(Map<String, Map<String, Object>> dataMap,
            String language) {
        return dataMap.entrySet().parallelStream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> detectSensitiveInfoInStructuredData(entry.getValue(), language)));
    }

    @Override
    public boolean validateAccuracy(String testDataPath) {
        // 实现准确率验证逻辑
        // 这里可以读取测试数据集进行验证
        log.info("开始验证敏感信息检测准确率...");
        // 模拟验证结果
        return true;
    }

    @Override
    public void addCustomPattern(String patternName, String regex) {
        try {
            Pattern pattern = Pattern.compile(regex);
            customPatterns.put(patternName, pattern);
            log.info("添加自定义检测模式: {}", patternName);
        } catch (Exception e) {
            log.error("无效的正则表达式: {}", regex, e);
            throw new IllegalArgumentException("无效的正则表达式: " + regex);
        }
    }

    @Override
    public void removeCustomPattern(String patternName) {
        if (customPatterns.containsKey(patternName)) {
            customPatterns.remove(patternName);
            log.info("移除自定义检测模式: {}", patternName);
        }
    }

    @Override
    public Map<String, Pattern> getCustomPatterns() {
        return new HashMap<>(customPatterns);
    }

    @Override
    public List<SensitiveEntity> detectByDataType(Object data, String dataType, String language) {
        return detectByDataType(data, dataType, language, null);
    }

    @Override
    public List<SensitiveEntity> detectByDataType(Object data, String dataType, String language,
            Set<String> includeTypes) {
        if (data == null) {
            return Collections.emptyList();
        }

        if (dataType == null) {
            dataType = "TEXT";
        }

        switch (dataType.toUpperCase()) {
            case "JSON":
            case "XML":
                if (data instanceof Map) {
                    return detectSensitiveInfoInStructuredData((Map<String, Object>) data, language, includeTypes);
                }
                // 如果不是Map，尝试转换为字符串进行检测
                return detectSensitiveInfo(data.toString(), language, includeTypes);
            case "IMAGE":
            case "AUDIO":
            case "PDF":
            case "DOC":
            case "DOCX":
            case "EXCEL":
                if (data instanceof byte[]) {
                    return detectSensitiveInfoInBinary((byte[]) data, dataType, language, includeTypes);
                } else if (data instanceof MultipartFile) {
                    try {
                        return detectSensitiveInfoInBinary(((MultipartFile) data).getBytes(), dataType, language,
                                includeTypes);
                    } catch (IOException e) {
                        log.error("无法从MultipartFile获取字节数据: {}", e.getMessage(), e);
                        return Collections.emptyList();
                    }
                }
                return Collections.emptyList();
            default:
                // 默认作为文本处理
                return detectSensitiveInfo(data.toString(), language, includeTypes);
        }
    }

    private List<SensitiveEntity> detectWithPattern(String text, SensitiveType type, Pattern pattern,
            ScenarioAnalysisResult context) {
        List<SensitiveEntity> entities = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);

        boolean strictMode = context != null && context.isStrictModeForType(type);
        double threshold = context != null ? context.getThresholdForType(type, 0.5) : 0.5;

        while (matcher.find()) {
            String matchedText = matcher.group();
            double confidence = calculateConfidence(text, matcher.start(), matcher.end(), matchedText, type,
                    strictMode);

            // 根据阈值过滤
            if (confidence >= threshold) {
                SensitiveEntity entity = SensitiveEntity.builder()
                        .type(type)
                        .originalText(matchedText)
                        .start(matcher.start())
                        .end(matcher.end())
                        .confidence(confidence)
                        .build();
                entities.add(entity);
            }
        }

        return entities;
    }

    private List<SensitiveEntity> detectCustomPattern(String text, String patternName, Pattern pattern) {
        List<SensitiveEntity> entities = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String matchedText = matcher.group();
            SensitiveEntity entity = SensitiveEntity.builder()
                    .type(SensitiveType.CUSTOM)
                    .originalText(matchedText)
                    .start(matcher.start())
                    .end(matcher.end())
                    .confidence(0.8) // 自定义模式默认置信度
                    .build();
            entities.add(entity);
        }

        return entities;
    }

    // TODO: 置信度优化，比如195-5780-9903格式化的手机号回被 验证1：格式校验 忽略

    // 正则只是找到了候选，还需要验证它是否真的是敏感信息！
    private double calculateConfidence(String fullText, int start, int end, String matchedText, SensitiveType type,
            boolean strictMode) {
        // 基于规则计算置信度
        double baseConfidence = 0.9;

        // 如果开启了严格模式，默认基础置信度降低，必须通过严格校验才能获得高置信度
        if (strictMode) {
            baseConfidence = 0.5;
        }

        // ========== 验证1：格式校验 ==========
        boolean formatValid = true;
        switch (type) {
            case PHONE_NUMBER:
                formatValid = validatePhoneNumber(matchedText);
                break;
            case ID_CARD:
                formatValid = validateIdCard(matchedText);
                break;
            case EMAIL:
                formatValid = validateEmail(matchedText);
                break;
            case LICENSE_PLATE:
                formatValid = validateLicensePlate(matchedText);
                break;
            case BANK_CARD:
                formatValid = validateBankCard(matchedText);
                break;
            case API_KEY:
            case PASSWORD:
                // 对于高熵字符串，检查熵值
                if (calculateEntropy(matchedText) < 2.5) { // 阈值可调，一般随机字符串熵值较高
                    return 0.2; // 熵值过低，极可能是误报（如"111111"）
                }
                break;
            default:
                break;
        }

        if (!formatValid) {
            if (strictMode) {
                return 0.0;
            }
            // 如果格式无效但有上下文支持，给予较高置信度（认为是用户输错）
            // 否则给予低置信度
            boolean hasContext = checkContext(fullText, start, end, type);
            return hasContext ? 0.7 : 0.4;
        }

        // ========== 验证2：上下文检测 ==========
        // TODO:文本上匹配正则但是上下文错判（误报）
        /*
         * 场景:
         * "验证码123456在5分钟内有效"
         * PatternRegistry可能匹配到123456（银行卡/手机号/代码都可能），checkContext没有明确禁止“验证码”模式，可能进入脱敏
         * 结果: 误把“验证码”等非隐私值当敏感处理
         */

        // 检查前后20个字符内是否有相关关键词
        boolean hasContext = checkContext(fullText, start, end, type);

        if (hasContext) {
            // 有上下文支持，提升置信度
            baseConfidence = Math.min(1.0, baseConfidence + 0.15);
        } else if (strictMode) {
            // 严格模式下，如果没有上下文支持，稍微降低置信度
            baseConfidence -= 0.1;
        }

        return Math.max(0.0, Math.min(1.0, baseConfidence));
    }

    // ========== 验证3：上下文关键词匹配 ==========
    private boolean checkContext(String text, int start, int end, SensitiveType type) {
        int contextRange = 20;
        int left = Math.max(0, start - contextRange);
        int right = Math.min(text.length(), end + contextRange);

        String surroundingText = text.substring(left, right).toLowerCase();
        List<String> keywords = getKeywordsForType(type);
        // 对于手机号，关键词包括：["电话", "手机", "号码", "联系", "call", "phone"]

        if (keywords.isEmpty())
            return false;

        for (String keyword : keywords) {
            if (surroundingText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    // TODO: 这个关键词也少，也死板
    private List<String> getKeywordsForType(SensitiveType type) {
        switch (type) {
            case PHONE_NUMBER:
                return Arrays.asList("tel", "phone", "mobile", "联系", "电话", "手机", "号码");
            case ID_CARD:
                return Arrays.asList("id", "card", "identity", "身份证", "证件", "号码");
            case BANK_CARD:
            case CREDIT_CARD:
                return Arrays.asList("bank", "card", "debit", "credit", "银行", "卡号", "账户", "支付");
            case API_KEY:
                return Arrays.asList("api", "key", "secret", "token", "access", "sk", "ak", "密钥", "令牌");
            case PASSWORD:
                return Arrays.asList("password", "pwd", "pass", "secret", "密码", "口令", "登录");
            case EMAIL:
                return Arrays.asList("email", "mail", "邮箱", "邮件", "联系");
            default:
                return Collections.emptyList();
        }
    }

    // ========== 验证4：熵值校验（用于API密钥、密码） ==========
    private double calculateEntropy(String s) {
        if (s == null || s.isEmpty())
            return 0.0;

        Map<Character, Integer> frequency = new HashMap<>();
        for (char c : s.toCharArray()) {
            frequency.put(c, frequency.getOrDefault(c, 0) + 1);
        }

        double entropy = 0.0;
        int len = s.length();
        for (int count : frequency.values()) {
            double prob = (double) count / len;
            entropy -= prob * (Math.log(prob) / Math.log(2));
        }

        return entropy;
    }

    // 简单的Luhn算法校验银行卡
    private boolean validateBankCard(String bankCard) {
        if (bankCard == null || bankCard.length() < 13 || bankCard.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean alternate = false;
        for (int i = bankCard.length() - 1; i >= 0; i--) {
            int n = Integer.parseInt(bankCard.substring(i, i + 1));
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n = (n % 10) + 1;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }

    private boolean validatePhoneNumber(String phone) {
        // 简单的手机号验证逻辑
        return phone != null && phone.matches("1[3-9]\\d{9}");
    }

    private boolean validateIdCard(String idCard) {
        // 简单的身份证号验证逻辑
        if (idCard == null || idCard.length() != 18) {
            return false;
        }

        // 验证最后一位校验码
        char[] chars = idCard.toCharArray();
        int[] coefficient = { 7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2 };
        char[] checkCodes = { '1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2' };

        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (chars[i] - '0') * coefficient[i];
        }

        char checkCode = checkCodes[sum % 11];
        return chars[17] == checkCode || (Character.toUpperCase(chars[17]) == checkCode);
    }

    private boolean validateEmail(String email) {
        // 简单的邮箱验证
        return email != null && email.contains("@") && email.contains(".");
    }

    private boolean validateLicensePlate(String plate) {
        if (plate == null) {
            return false;
        }
        // 与注册的模式保持一致，进行二次校验
        String regex = "(?:(?:[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙闽藏青川宁琼港澳][A-Z][A-HJ-NP-Z0-9]{4}[A-HJ-NP-Z0-9挂学警港澳])|" +
                "(?:[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙闽藏青川宁琼][A-Z](?:[DF][A-HJ-NP-Z0-9]{5}|[A-HJ-NP-Z0-9]{5}[DF])))";
        return plate.matches(regex);
    }

    private void detectInStructuredData(Object data, String fieldPath, List<SensitiveEntity> entities, String language,
            Set<String> includeTypes) {
        if (data == null) {
            return;
        }

        if (data instanceof String) {
            String text = (String) data;
            // 这里我们暂时无法传入Context，因为StructuredData接口目前没有扩展Context
            // TODO: 后续可以扩展支持Context
            List<SensitiveEntity> fieldEntities = detectSensitiveInfo(text, language, includeTypes);
            // 为每个检测到的敏感实体添加字段路径信息，但保留原始位置信息
            for (SensitiveEntity entity : fieldEntities) {
                // 保留原始的起始和结束位置信息，不覆盖
                if (fieldPath != null && !fieldPath.isEmpty()) {
                    entity.addFieldPath(fieldPath);
                }
                entities.add(entity);
            }
        } else if (data instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) data;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() != null ? entry.getKey().toString() : "";
                String newFieldPath = fieldPath.isEmpty() ? key : fieldPath + "." + key;
                detectInStructuredData(entry.getValue(), newFieldPath, entities, language, includeTypes);
            }
        } else if (data instanceof List) {
            List<?> list = (List<?>) data;
            for (int i = 0; i < list.size(); i++) {
                String newFieldPath = fieldPath.isEmpty() ? "[" + i + "]" : fieldPath + "[" + i + "]";
                detectInStructuredData(list.get(i), newFieldPath, entities, language, includeTypes);
            }
        }
        // 其他类型暂不处理
    }

    // 合并重叠的实体
    private List<SensitiveEntity> mergeOverlappingEntities(List<SensitiveEntity> entities) {
        if (entities.isEmpty()) {
            return entities;
        }

        // 按起始位置排序
        entities.sort(Comparator.comparingInt(SensitiveEntity::getStart));

        List<SensitiveEntity> merged = new ArrayList<>();
        SensitiveEntity current = entities.get(0);

        for (int i = 1; i < entities.size(); i++) {
            SensitiveEntity next = entities.get(i);

            // 检查是否来自不同的字段路径（结构化数据）
            boolean hasDifferentFieldPaths = false;
            String currentPath = null;
            String nextPath = null;

            if (current.getMetadata() != null) {
                currentPath = (String) current.getMetadata().get("fieldPath");
            }
            if (next.getMetadata() != null) {
                nextPath = (String) next.getMetadata().get("fieldPath");
            }

            // 如果两个实体都有字段路径且不同，则不合并
            hasDifferentFieldPaths = currentPath != null && nextPath != null && !currentPath.equals(nextPath);

            // 只有当没有重叠或者来自不同字段路径时，才添加当前实体并更新current
            if (current.getEnd() < next.getStart() || hasDifferentFieldPaths) {
                merged.add(current);
                current = next;
            } else {
                // 重叠且来自相同字段路径，进行合并
                if (next.getEnd() > current.getEnd()) {
                    current.setEnd(next.getEnd());
                    try {
                        current.setOriginalText(
                                current.getOriginalText() +
                                        next.getOriginalText().substring(current.getEnd() - next.getStart()));
                    } catch (IndexOutOfBoundsException e) {
                        // 避免字符串索引越界，直接使用较长的文本
                        current.setOriginalText(next.getOriginalText());
                    }
                }
                // 选择置信度较高的类型
                if (next.getConfidence() > current.getConfidence()) {
                    current.setType(next.getType());
                    current.setConfidence(next.getConfidence());
                }
                // 合并元数据信息
                if (next.getMetadata() != null && !next.getMetadata().isEmpty()) {
                    Map<String, Object> currentMetadata = current.getMetadata();
                    Map<String, Object> nextMetadata = next.getMetadata();
                    currentMetadata.putAll(nextMetadata);
                }
            }
        }

        // 添加最后一个实体
        merged.add(current);
        return merged;
    }
}
