package com.hdu.apisensitivities.service.desensitization;

import com.hdu.apisensitivities.service.Desensitization.GlobalSessionContextRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话级一致性缓存的"脱敏占位符 → 原始明文"反向映射测试。
 * <p>
 * 反向映射是前端解码还原 AI 返回内容中脱敏标记的数据源：
 * 前端将后端返回的 maskMapping 直接用于将 [PHONE_1]、[MASKED_1] 等标记还原为原始业务数据。
 */
class GlobalSessionContextRepositoryReverseMappingTest {

    private final GlobalSessionContextRepository repository = new GlobalSessionContextRepository();

    private void getOrCreate(String sessionId, String plain, String type) {
        repository.getOrCreateConsistencyValue(sessionId, plain, type,
                i -> "[TYPE_" + i + "]");
    }

    @Test
    void reverseMappingRoundTrip() {
        repository.getOrCreateConsistencyValue("s1", "13812345678", "PHONE_NUMBER",
                i -> "[PHONE_" + i + "]");
        repository.getOrCreateConsistencyValue("s1", "510104198303123639", "ID_CARD",
                i -> "[ID_CARD_" + i + "]");
        repository.getOrCreateConsistencyValue("s1", "张三", "NAME",
                i -> "[NAME_" + i + "]");

        Map<String, String> mapping = repository.getReverseMapping("s1");

        assertEquals(3, mapping.size(), "应包含 3 条反向映射");
        assertEquals("13812345678", mapping.get("[PHONE_1]"));
        assertEquals("510104198303123639", mapping.get("[ID_CARD_1]"));
        assertEquals("张三", mapping.get("[NAME_1]"));
    }

    @Test
    void reverseMappingReusesConsistencyValue() {
        // 同一会话内同一明文复用同一占位符 → 反向映射仍只有一条
        getOrCreate("s1", "张三", "NAME");
        getOrCreate("s1", "张三", "NAME");
        getOrCreate("s1", "李四", "NAME");

        Map<String, String> mapping = repository.getReverseMapping("s1");
        assertEquals(2, mapping.size());
        assertEquals("张三", mapping.get("[TYPE_1]"));
        assertEquals("李四", mapping.get("[TYPE_2]"));
    }

    @Test
    void reverseMappingSessionIsolation() {
        getOrCreate("s1", "13812345678", "PHONE_NUMBER");
        getOrCreate("s2", "13900000000", "PHONE_NUMBER");

        Map<String, String> m1 = repository.getReverseMapping("s1");
        Map<String, String> m2 = repository.getReverseMapping("s2");

        assertEquals(1, m1.size());
        assertEquals(1, m2.size());
        assertEquals("13812345678", m1.get("[TYPE_1]"));
        assertEquals("13900000000", m2.get("[TYPE_1]"));
        // 两个会话各自独立编号，不会互相污染
        assertEquals("[TYPE_1]", m2.keySet().iterator().next());
    }

    @Test
    void reverseMappingUnknownOrEmptySession() {
        assertTrue(repository.getReverseMapping("not-exist").isEmpty());
        assertTrue(repository.getReverseMapping(null).isEmpty());
        assertTrue(repository.getReverseMapping("").isEmpty());
    }

    @Test
    void reverseMappingMultipleValuesPerType() {
        getOrCreate("s1", "13800000001", "PHONE_NUMBER");
        getOrCreate("s1", "13800000002", "PHONE_NUMBER");

        Map<String, String> mapping = repository.getReverseMapping("s1");
        assertEquals(2, mapping.size());
        assertEquals("13800000001", mapping.get("[TYPE_1]"));
        assertEquals("13800000002", mapping.get("[TYPE_2]"));
        assertFalse(mapping.containsKey("[TYPE_3]"));
    }
}
