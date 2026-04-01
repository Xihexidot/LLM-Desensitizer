package com.hdu.apisensitivities.benchmark;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class TestDataDTO {
    private String id;
    private String content;
    private String language;
    private List<Map<String, Object>> expected_entities; // 对应 JSON 中的 entities 数组
}