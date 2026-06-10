package com.hdu.apisensitivities.service.Desensitization;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@Component
public class GlobalSessionContextRepository {

    // 外层 Key: sessionId
    // 内层 Key: "敏感类型:原始明文" (例如 "NAME:张三")
    // Value: 最终的脱敏替换词
    private final Map<String, Map<String, String>> sessionCache = new ConcurrentHashMap<>();

    // 记录每个会话内各个类型的自增计数。Key: sessionId, 内层 Key: 敏感类型, Value: 当前最大序号
    private final Map<String, Map<String, AtomicInteger>> typeCounters = new ConcurrentHashMap<>();

    /**
     * 一致性核心方法
     * 
     * @param sessionId     会话ID
     * @param originalText  原始文本（如："张三"）
     * @param typeStr       敏感类型字符串（如："NAME"）
     * @param valueProvider 闭包函数。如果是新词，该函数定义了如何生成脱敏文本，入参是分配到的自增序号
     */
    public String getOrCreateConsistencyValue(String sessionId, String originalText, String typeStr,
            Function<Integer, String> valueProvider) {
        // 兜底防御，确保不报空指针
        String safeSessionId = (sessionId == null || sessionId.isEmpty()) ? "GLOBAL_DEFAULT" : sessionId;
        String cacheKey = typeStr + ":" + originalText;

        // 内存保护，防止本地压测或长期运行导致 OOM
        if (sessionCache.size() > 5000) {
            sessionCache.clear();
            typeCounters.clear();
        }

        // 1. 获取当前会话的专属缓存空间
        Map<String, String> currentSessionMap = sessionCache.computeIfAbsent(safeSessionId,
                k -> new ConcurrentHashMap<>());

        // 2. 一致性命中：如果之前处理过这个词，直接原样返回历史脱敏值
        if (currentSessionMap.containsKey(cacheKey)) {
            return currentSessionMap.get(cacheKey);
        }

        // 3. 一致性未命中（新词）：分配或递增这个敏感类型在当前会话下的独立序号
        Map<String, AtomicInteger> sessionCounters = typeCounters.computeIfAbsent(safeSessionId,
                k -> new ConcurrentHashMap<>());
        AtomicInteger counter = sessionCounters.computeIfAbsent(typeStr, k -> new AtomicInteger(0));
        int currentId = counter.incrementAndGet();

        // 4. 调用具体策略传入的计算逻辑，生成脱敏结果（比如把自增序号拼进模板、或者部分转为*）
        String finalMaskedValue = valueProvider.apply(currentId);

        // 5. 存入缓存
        currentSessionMap.put(cacheKey, finalMaskedValue);
        return finalMaskedValue;
    }
}