package com.hdu.apisensitivities.service.desensitization;

import com.hdu.apisensitivities.service.Desensitization.SemanticPlaceholderStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SemanticPlaceholderStrategy 单元测试。
 * 重点验证：desensitize/restore 基本逻辑、长实体优先替换、
 * 以及 ThreadLocal 映射表是否会在不同请求间残留（跨请求数据泄露）。
 */
class SemanticPlaceholderStrategyTest {

    private final SemanticPlaceholderStrategy strategy = new SemanticPlaceholderStrategy();

    // ==================== desensitize 基本逻辑 ====================

    @Test
    void desensitize_shouldReplaceEntitiesWithPlaceholders() {
        String result = strategy.desensitize("张三在阿里巴巴工作", List.of("张三", "阿里巴巴"));

        // 长实体优先："阿里巴巴"(4字) → [ENTITY_1]，"张三"(2字) → [ENTITY_2]
        assertEquals("[ENTITY_2]在[ENTITY_1]工作", result);
    }

    @Test
    void desensitize_shouldReplaceLongEntitiesFirst() {
        // 长实体优先，防止"李华"把"李华强"切断
        String result = strategy.desensitize("李华强和李华是同事", List.of("李华", "李华强"));

        // 先替换"李华强"→[ENTITY_1]，再替换剩余的"李华"→[ENTITY_2]
        assertEquals("[ENTITY_1]和[ENTITY_2]是同事", result);
    }

    @Test
    void desensitize_shouldReturnOriginalText_whenEntitiesEmpty() {
        String result = strategy.desensitize("你好，今天天气不错", List.of());

        assertEquals("你好，今天天气不错", result);
    }

    @Test
    void desensitize_shouldReturnOriginalText_whenNullText() {
        String result = strategy.desensitize(null, List.of("张三"));

        assertNull(result);
    }

    @Test
    void desensitize_shouldMaskMultipleOccurrences() {
        String result = strategy.desensitize("张三联系张三", List.of("张三"));

        assertEquals("[ENTITY_1]联系[ENTITY_1]", result);
    }

    // ==================== restore 基本逻辑 ====================

    @Test
    void restore_shouldReplacePlaceholdersBackToOriginal() {
        strategy.desensitize("张三在阿里巴巴", List.of("张三", "阿里巴巴"));
        // 映射：[ENTITY_1]→阿里巴巴(长)，[ENTITY_2]→张三(短)
        String result = strategy.restore("[ENTITY_1]在[ENTITY_2]的年度会议上发言");

        assertEquals("阿里巴巴在张三的年度会议上发言", result);
    }

    @Test
    void restore_shouldReturnTextUnchanged_whenNoPlaceholders() {
        strategy.desensitize("张三在阿里巴巴", List.of("张三", "阿里巴巴"));
        String result = strategy.restore("这是一个普通的回复内容");

        assertEquals("这是一个普通的回复内容", result);
    }

    // ==================== 🔴 跨请求泄露测试（关键安全场景） ====================

    /**
     * 场景模拟：用户A的请求包含敏感实体（被存入映射表），
     * 用户B的请求没有敏感实体。线程池复用时，
     * 如果 mappingTable 未被清空，用户B的响应可能被还原成用户A的敏感数据。
     * 修复前此测试会失败（泄露），修复后应通过。
     */
    @Test
    void restore_shouldNotLeakPreviousRequestData_whenCurrentRequestHasNoEntities() {
        // —— 请求1：用户A，包含敏感实体 ——
        strategy.desensitize("张三的电话是13800138000", List.of("张三", "13800138000"));

        // —— 请求2：用户B，没有敏感实体（aiEntities 为空）——
        // desensitize 直接返回原文，此时必须清空上一请求的映射表
        strategy.desensitize("普通问题，没有敏感内容", List.of());

        // 用户B的 LLM 响应即使出现 [ENTITY_1] 字样，也不应还原成用户A的"张三"
        String result = strategy.restore("好的，我看到了[ENTITY_1]这个占位符");

        // 修复前：这里会还原成"张三"（泄露！）；修复后应保持原文
        assertEquals("好的，我看到了[ENTITY_1]这个占位符", result,
                "跨请求敏感数据泄露！用户B的响应不应还原用户A的敏感数据");
    }

    /**
     * 场景模拟：连续两个请求都有实体，但实体不同。
     * 第二个请求 restore 时，映射表必须被第二个请求覆盖，不能使用第一个的。
     */
    @Test
    void restore_shouldUseCurrentRequestMapping_notPrevious() {
        // 请求1：用户A
        strategy.desensitize("张三", List.of("张三"));
        // 请求2：用户B
        strategy.desensitize("李四", List.of("李四"));

        String result = strategy.restore("[ENTITY_1]您好");

        assertEquals("李四您好", result, "必须使用当前请求（李四）的映射，而不是上一个请求（张三）");
    }

    /**
     * 场景模拟：Agent 返回 null 实体（异常降级路径）。
     * 修复前 `entities == null` 分支提前返回且不清空映射表，
     * 用户B的响应会被还原成用户A的敏感数据。修复后必须清空。
     */
    @Test
    void restore_shouldNotLeakPreviousRequestData_whenEntitiesNull() {
        // 请求1：用户A，包含敏感实体 → 映射表 {[ENTITY_1]→张三}
        strategy.desensitize("张三的电话是13800138000", List.of("张三", "13800138000"));

        // 请求2：用户B，Agent 降级返回 null（而非空列表）
        strategy.desensitize("普通问题", (List<String>) null);

        // 用户B的响应即使含 [ENTITY_1]，也不应还原成用户A的"张三"
        String result = strategy.restore("好的，我看到了[ENTITY_1]这个占位符");
        assertEquals("好的，我看到了[ENTITY_1]这个占位符", result,
                "跨请求敏感数据泄露！entities=null 分支必须清空映射表");
    }

    /**
     * 场景模拟：restore 前映射表未被 desensitize 填充（例如直接调用）。
     * 此时不应发生任何替换。
     */
    @Test
    void restore_shouldReturnTextUnchanged_whenNoMappingEstablished() {
        String result = strategy.restore("包含[ENTITY_1]的文本");

        assertEquals("包含[ENTITY_1]的文本", result);
    }
}
