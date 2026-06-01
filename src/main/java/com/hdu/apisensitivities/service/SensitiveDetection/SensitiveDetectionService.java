package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface SensitiveDetectionService extends TextSensitiveDetectionService,
        StructuredSensitiveDetectionService,
        BinarySensitiveDetectionService,
        CustomPatternDetectionService {

    /**
     * 根据数据类型检测敏感信息
     * @param data 待检测数据（可以是String、Map、byte[]等）
     * @param dataType 数据类型（TEXT、JSON、XML、IMAGE、AUDIO等）
     * @param language 语言类型
     * @return 敏感信息实体列表
     */
    List<SensitiveEntity> detectByDataType(Object data, String dataType, String language);

    /**
     * 根据数据类型检测敏感信息（按指定类型范围）
     * @param data 待检测数据（可以是String、Map、byte[]等）
     * @param dataType 数据类型（TEXT、JSON、XML、IMAGE、AUDIO等）
     * @param language 语言类型
     * @param includeTypes 需要检测的敏感类型集合
     * @return 敏感信息实体列表
     */
    List<SensitiveEntity> detectByDataType(Object data, String dataType, String language, Set<String> includeTypes);

    /**
     * 批量检测文本中的敏感信息
     * @param texts 待检测文本列表
     * @param language 语言类型
     * @return 文本和对应敏感信息实体列表的映射
     */
    Map<String, List<SensitiveEntity>> batchDetect(List<String> texts, String language);

    /**
     * 批量检测文本中的敏感信息（按指定类型范围）
     * @param texts 待检测文本列表
     * @param language 语言类型
     * @param includeTypes 需要检测的敏感类型集合
     * @return 文本和对应敏感信息实体列表的映射
     */
    Map<String, List<SensitiveEntity>> batchDetect(List<String> texts, String language, Set<String> includeTypes);

    /**
     * 批量检测结构化数据中的敏感信息
     * @param dataMap 待检测的结构化数据映射
     * @param language 语言类型
     * @return 键和对应敏感信息实体列表的映射
     */
    Map<String, List<SensitiveEntity>> batchDetectStructuredData(Map<String, Map<String, Object>> dataMap, String language);

    /**
     * 验证检测准确率
     * @param testDataPath 测试数据路径
     * @return 准确率是否通过
     */
    boolean validateAccuracy(String testDataPath);

}
