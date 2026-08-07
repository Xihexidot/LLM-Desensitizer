package com.hdu.apisensitivities.service.Desensitization;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.utils.CollectionTypeUtils;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component("semanticPlaceholderStrategy")
public class SemanticPlaceholderStrategy implements DesensitizationStrategy {

    // 🌟 关键：使用 ThreadLocal 存储映射表，确保并发安全（每个请求有自己的小票存根）
    private final ThreadLocal<Map<String, String>> mappingTable = ThreadLocal.withInitial(LinkedHashMap::new);

    @Override
    public String desensitize(String text, List<SensitiveEntity> sensitiveEntities) {
        // 语义占位符策略面向字符串实体：将 SensitiveEntity 统一转为名称列表后委托给
        // varargs 实现，确保任何分支（含空列表 / null）都会清空映射表，防止跨请求残留。
        List<String> names = sensitiveEntities == null ? null
                : sensitiveEntities.stream()
                        .map(SensitiveEntity::getContent)
                        .collect(Collectors.toList());
        return desensitize(text, names);
    }

    @Override
    public Map<String, Object> desensitizeStructuredData(Map<String, Object> structuredData,
            List<SensitiveEntity> sensitiveEntities) {
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
        // 🔒 安全修复：无条件先清空上一请求的映射表。
        // 若不清空，在 LLM 调用异常中断、实体列表为 null、参数非法等提前返回分支下，
        // ThreadLocal 中残留的映射会泄漏给同一线程的下一个请求（跨请求敏感数据泄露）。
        Map<String, String> currentMap = mappingTable.get();
        currentMap.clear();

        if (text == null || args.length == 0 || !(args[0] instanceof List)) {
            return text;
        }

        List<String> entities = CollectionTypeUtils.asStringList(args[0]);
        if (entities == null) {
            return text;
        }

        // 🌟 避坑指南：先按长度降序排列，防止“李华”把“李华强”切断
        entities.sort((a, b) -> Integer.compare(b.length(), a.length()));

        String maskedText = text;

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

    /**
     * 获取当前线程"占位符 → 实体文本"映射的只读副本。
     * <p>
     * 供外层在完成 restore 后，将 AI 链路脱敏标记（{@code [ENTITY_N]}）与其对应的
     * 实体文本一并导出给前端解码还原，实现 AI 脱敏结果的可逆闭环。
     * </p>
     *
     * @return 当前请求线程的脱敏标记映射副本；无映射时为空 Map（非 null）
     */
    public Map<String, String> getCurrentMapping() {
        return new LinkedHashMap<>(mappingTable.get());
    }
}
