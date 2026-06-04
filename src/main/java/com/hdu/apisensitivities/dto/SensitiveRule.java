package com.hdu.apisensitivities.dto;


import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SensitiveRule {
    private Long id;
    private String patternName;
    private String regex;
    private Boolean isEnabled;
    private String description;
    private String ruleType;   // BUILTIN（覆盖内置规则）/ CUSTOM（用户新增）
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
