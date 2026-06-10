package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.ScenarioPerception.ScenarioAnalysisResult;
import com.hdu.apisensitivities.utils.CollectionTypeUtils;
import com.hdu.apisensitivities.utils.PatternRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 基于正则表达式、NLP 和自定义模式的敏感信息检测服务实现。
 * <p>
 * 该类是 {@link SensitiveDetectionService} 的主要实现，提供文本、结构化数据和二进制数据中的敏感实体检测能力。
 * 检测流程包括：
 * <ol>
 * <li>使用预定义正则模式（通过 {@link PatternRegistry}）匹配常见敏感类型（手机号、身份证、邮箱等）；</li>
 * <li>调用 HanLP NLP 引擎识别命名实体（人名、地名、机构等）；</li>
 * <li>支持用户自定义正则模式（通过 {@link #addCustomPattern(String, String)}）；</li>
 * <li>对所有匹配项进行置信度评估（格式校验、上下文关键词、信息熵），并根据动态阈值过滤；</li>
 * <li>合并重叠的敏感实体，避免重复脱敏。</li>
 * </ol>
 * </p>
 * <p>
 * 该类支持情景感知（通过 {@link ScenarioAnalysisResult} 参数）动态调整严格模式和置信度阈值，
 * 若情景参数为 {@code null}，则使用默认宽松配置（严格模式关闭，阈值 0.5）。
 * </p>
 *
 * @author yourname
 * @since 1.0.0
 * @see SensitiveDetectionService
 * @see PatternRegistry
 * @see NlpEntityDetector
 */
@Slf4j
@Service
@Primary
public class RegexDetectionService implements SensitiveDetectionService {
    @Autowired
    private CustomPatternManager customPatternManager;
    @Autowired
    private NlpDetectionService nlpDetectionService;
    @Autowired
    private RegexPatternDetector regexPatternDetector;
    @Autowired
    private StructuredDataDetector structuredDataDetector;
    @Autowired
    private BinaryDataDetector binaryDataDetector;
    @Autowired
    private EntityMerger entityMerger;

    // ======================== 文本检测核心方法 ========================

    /**
     * 检测文本中的敏感信息（使用默认语言和所有敏感类型）。
     *
     * @param text     待检测文本
     * @param language 语言类型（如 "zh", "en"），当前实现未直接使用，预留扩展
     * @return 敏感实体列表，若无则返回空列表
     */
    @Override
    public List<SensitiveEntity> detectSensitiveInfo(String text, String language) {
        return detectSensitiveInfo(text, language, null, null);
    }

    /**
     * 检测文本中的敏感信息（按指定类型范围）。
     *
     * @param text         待检测文本
     * @param language     语言类型
     * @param includeTypes 需要检测的敏感类型名称集合，若为 {@code null} 则检测所有类型
     * @return 敏感实体列表
     */
    @Override
    public List<SensitiveEntity> detectSensitiveInfo(String text, String language, Set<String> includeTypes) {
        return detectSensitiveInfo(text, language, includeTypes, null);
    }

    /**
     * 检测文本中的敏感信息（基于情景分析结果，支持动态阈值和严格模式）。
     * <p>
     * 这是所有文本检测方法的核心实现。处理步骤：
     * <ol>
     * <li>从 {@link PatternRegistry} 获取预定义正则模式，根据 {@code includeTypes} 过滤后执行匹配；</li>
     * <li>调用 {@link NlpEntityDetector#detect(String)} 进行 NLP 实体识别；</li>
     * <li>匹配用户自定义模式（若 {@code includeTypes} 包含 {@code CUSTOM} 或为 {@code null}）；</li>
     * <li>对每个匹配项计算置信度（格式校验、上下文关键词、信息熵），并根据情景参数动态决定阈值；</li>
     * <li>合并重叠或相邻的实体。</li>
     * </ol>
     * </p>
     *
     * @param text         待检测文本
     * @param language     语言类型
     * @param includeTypes 需要检测的敏感类型名称集合，{@code null} 表示全部
     * @param context      情景分析结果，用于获取特定类型的严格模式和置信度阈值；若为 {@code null}
     *                     则使用默认值（严格模式关闭，阈值 0.5）
     * @return 经过置信度过滤和合并后的敏感实体列表
     */
    @Override
    public List<SensitiveEntity> detectSensitiveInfo(String text, String language, Set<String> includeTypes,
            ScenarioAnalysisResult context) {
        log.info("检测文本: {}", text);
        log.info("includeTypes: {}", includeTypes);
        log.info("context: {}", context);
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<SensitiveEntity> entities = new ArrayList<>();

        collectPredefinedPatternEntities(text, includeTypes, context, entities);
        collectNlpEntities(text, includeTypes, entities);
        collectCustomPatternEntities(text, includeTypes, entities);

        return entityMerger.mergeEntities(entities);
    }

    private void collectPredefinedPatternEntities(String text, Set<String> includeTypes,
            ScenarioAnalysisResult context, List<SensitiveEntity> entities) {
        for (Map.Entry<SensitiveType, Pattern> entry : PatternRegistry.getAllPatterns().entrySet()) {
            if (shouldDetectType(includeTypes, entry.getKey().name())) {
                entities.addAll(regexPatternDetector.detectWithPattern(text, entry.getKey(), entry.getValue(), context));
            }
        }
    }

    private void collectNlpEntities(String text, Set<String> includeTypes, List<SensitiveEntity> entities) {
        List<SensitiveEntity> nlpEntities = nlpDetectionService.detect(text);
        for (SensitiveEntity entity : nlpEntities) {
            if (shouldDetectType(includeTypes, entity.getType().name())) {
                entities.add(entity);
            }
        }
    }

    private void collectCustomPatternEntities(String text, Set<String> includeTypes, List<SensitiveEntity> entities) {
        if (!shouldDetectType(includeTypes, SensitiveType.CUSTOM.name())) {
            return;
        }

        for (Map.Entry<String, Pattern> entry : customPatternManager.getAllPatterns().entrySet()) {
            entities.addAll(regexPatternDetector.detectCustomPattern(text, entry.getKey(), entry.getValue()));
        }
    }

    private boolean shouldDetectType(Set<String> includeTypes, String typeName) {
        return includeTypes == null || includeTypes.contains(typeName);
    }

    // ======================== 结构化数据检测 ========================

    /**
     * 检测结构化数据（如 JSON/XML 解析后的 Map）中的敏感信息。
     *
     * @param structuredData 结构化数据 Map，键为字段名，值为对应数据
     * @param language       语言类型
     * @return 敏感实体列表，每个实体会通过元数据记录其字段路径（如 "user.phone"）
     */
    @Override
    public List<SensitiveEntity> detectSensitiveInfoInStructuredData(Map<String, Object> structuredData,
            String language) {
        return detectSensitiveInfoInStructuredData(structuredData, language, null);
    }

    /**
     * 检测结构化数据中的敏感信息（按指定类型范围）。
     *
     * @param structuredData 结构化数据
     * @param language       语言类型
     * @param includeTypes   需要检测的敏感类型集合
     * @return 敏感实体列表
     */
    @Override
    public List<SensitiveEntity> detectSensitiveInfoInStructuredData(Map<String, Object> structuredData,
            String language, Set<String> includeTypes) {
        if (structuredData == null || structuredData.isEmpty()) {
            return Collections.emptyList();
        }

        List<SensitiveEntity> entities = new ArrayList<>();
        // 深度遍历结构化数据进行敏感信息检测（委托给 StructuredDataDetector）
        structuredDataDetector.detectInStructuredData(structuredData, "", entities, language, includeTypes, this);
        return entityMerger.mergeEntities(entities);
    }

    // ======================== 二进制数据检测 ========================

    /**
     * 检测二进制数据（如图片、PDF、音频等）中的敏感信息。
     * <p>
     * 内部通过 {@link DataParserManager#parseMultipartFile} 提取文本内容，然后调用文本检测方法。
     * </p>
     *
     * @param binaryData 二进制数据字节数组
     * @param dataType   数据类型（如 "PDF", "IMAGE", "AUDIO"）
     * @param language   语言类型
     * @return 敏感实体列表，每个实体元数据中会添加 "binaryDataType" 字段
     */
    @Override
    public List<SensitiveEntity> detectSensitiveInfoInBinary(byte[] binaryData, String dataType, String language) {
        return detectSensitiveInfoInBinary(binaryData, dataType, language, null);
    }

    /**
     * 检测二进制数据中的敏感信息（按指定类型范围）。
     *
     * @param binaryData   二进制数据
     * @param dataType     数据类型
     * @param language     语言类型
     * @param includeTypes 需要检测的敏感类型集合
     * @return 敏感实体列表
     */
    @Override
    public List<SensitiveEntity> detectSensitiveInfoInBinary(byte[] binaryData, String dataType, String language,
            Set<String> includeTypes) {
        return binaryDataDetector.detectBinary(binaryData, dataType, language, includeTypes, this);
    }

    // ======================== 批量检测 ========================

    /**
     * 批量检测文本列表中的敏感信息。
     *
     * @param texts    待检测文本列表
     * @param language 语言类型
     * @return 以原始文本为键、敏感实体列表为值的映射
     */
    @Override
    public Map<String, List<SensitiveEntity>> batchDetect(List<String> texts, String language) {
        return batchDetect(texts, language, null);
    }

    /**
     * 批量检测文本列表中的敏感信息（按指定类型范围）。
     *
     * @param texts        待检测文本列表
     * @param language     语言类型
     * @param includeTypes 需要检测的敏感类型集合
     * @return 文本到敏感实体列表的映射
     */
    @Override
    public Map<String, List<SensitiveEntity>> batchDetect(List<String> texts, String language,
            Set<String> includeTypes) {
        return texts.parallelStream()
                .collect(Collectors.toMap(
                        text -> text,
                        text -> detectSensitiveInfo(text, language, includeTypes)));
    }

    /**
     * 批量检测结构化数据映射中的敏感信息。
     *
     * @param dataMap  以标识符为键、结构化数据为值的映射
     * @param language 语言类型
     * @return 标识符到敏感实体列表的映射
     */
    @Override
    public Map<String, List<SensitiveEntity>> batchDetectStructuredData(Map<String, Map<String, Object>> dataMap,
            String language) {
        return dataMap.entrySet().parallelStream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> detectSensitiveInfoInStructuredData(entry.getValue(), language)));
    }

    // ======================== 自定义模式管理 ========================

    /**
     * 添加自定义检测模式。
     * <p>
     * 自定义模式会与预定义模式一起参与检测，其敏感类型固定为 {@link SensitiveType#CUSTOM}，
     * 置信度固定为 0.8。若正则表达式无效，将抛出 {@link IllegalArgumentException}。
     * </p>
     *
     * @param patternName 模式名称，用于标识和移除
     * @param regex       正则表达式字符串
     * @throws IllegalArgumentException 如果正则表达式编译失败
     */
    @Override
    public void addCustomPattern(String patternName, String regex) {
        try {
            customPatternManager.addCustomPattern(patternName, regex);
            log.info("添加自定义检测模式: {}", patternName);
        } catch (Exception e) {
            log.error("无效的正则表达式: {}", regex, e);
            throw new IllegalArgumentException("无效的正则表达式: " + regex);
        }
    }

    /**
     * 移除自定义检测模式。
     *
     * @param patternName 要移除的模式名称
     */
    @Override
    public void removeCustomPattern(String patternName) {
        customPatternManager.removeCustomPattern(patternName);
        log.info("移除自定义检测模式: {}", patternName);
    }

    /**
     * 获取所有已注册的自定义检测模式。
     *
     * @return 模式名称到 {@link Pattern} 对象的只读副本
     */
    @Override
    public Map<String, Pattern> getCustomPatterns() {
        return customPatternManager.getAllPatterns();
    }

    // ======================== 数据类型统一检测入口 ========================

    /**
     * 根据数据类型自动选择合适的检测方法。
     * <p>
     * 支持的数据类型：
     * <ul>
     * <li>TEXT / 默认：调用 {@link #detectSensitiveInfo(String, String, Set)}</li>
     * <li>JSON / XML：若数据为 {@link Map} 则调用结构化检测，否则转为字符串检测</li>
     * <li>IMAGE / AUDIO / PDF / DOC / DOCX / EXCEL：调用二进制检测</li>
     * </ul>
     * </p>
     *
     * @param data     待检测数据，可以是 String、Map 或 byte[]
     * @param dataType 数据类型（不区分大小写）
     * @param language 语言类型
     * @return 敏感实体列表
     */
    @Override
    public List<SensitiveEntity> detectByDataType(Object data, String dataType, String language) {
        return detectByDataType(data, dataType, language, null);
    }

    /**
     * 根据数据类型检测敏感信息（按指定类型范围）。
     *
     * @param data         待检测数据
     * @param dataType     数据类型
     * @param language     语言类型
     * @param includeTypes 需要检测的敏感类型集合
     * @return 敏感实体列表
     */
    @Override
    public List<SensitiveEntity> detectByDataType(Object data, String dataType, String language,
            Set<String> includeTypes) {
        if (data == null) {
            return Collections.emptyList();
        }

        String normalizedDataType = dataType == null ? "TEXT" : dataType.toUpperCase();

        switch (normalizedDataType) {
            case "JSON":
            case "XML":
                Map<String, Object> structuredData = CollectionTypeUtils.asStringObjectMap(data);
                if (structuredData != null) {
                    return detectSensitiveInfoInStructuredData(structuredData, language, includeTypes);
                }
                return detectSensitiveInfo(data.toString(), language, includeTypes);
            case "IMAGE":
            case "AUDIO":
            case "PDF":
            case "DOC":
            case "DOCX":
            case "EXCEL":
                return detectBinaryDataByType(data, normalizedDataType, language, includeTypes);
            default:
                return detectSensitiveInfo(data.toString(), language, includeTypes);
        }
    }

    private List<SensitiveEntity> detectBinaryDataByType(Object data, String dataType, String language,
            Set<String> includeTypes) {
        if (data instanceof byte[]) {
            return detectSensitiveInfoInBinary((byte[]) data, dataType, language, includeTypes);
        }

        if (data instanceof MultipartFile) {
            try {
                return detectSensitiveInfoInBinary(((MultipartFile) data).getBytes(), dataType, language,
                        includeTypes);
            } catch (IOException e) {
                log.error("无法从MultipartFile获取字节数据: {}", e.getMessage(), e);
            }
        }

        return Collections.emptyList();
    }

    /**
     * 验证检测准确率（目前为占位实现）。
     * TODO: 啥玩意占位
     *
     * @param testDataPath 测试数据路径
     * @return 总是返回 {@code true}，实际未实现验证逻辑
     */
    @Override
    public boolean validateAccuracy(String testDataPath) {
        log.info("开始验证敏感信息检测准确率...");
        return true;
    }
}
