package com.hdu.apisensitivities.config;

import com.hdu.apisensitivities.entity.SensitiveLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 脱敏规则动态配置（application-*.properties 可热调整，无需改代码）。
 * <p>
 * 配置前缀：{@code desensitization.rule}，示例：
 * <pre>
 * desensitization.rule.graded-enabled=true
 * desensitization.rule.type-levels[ID_CARD]=HIGH
 * desensitization.rule.type-levels[PHONE_NUMBER]=MEDIUM
 * desensitization.rule.verify-enabled=true
 * desensitization.rule.manual-review-enabled=true
 * desensitization.rule.coverage-threshold=0.9
 * </pre>
 * </p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "desensitization.rule")
public class DesensitizationRuleProperties {

    /** 分级分类脱敏总开关 */
    private boolean gradedEnabled = true;

    /** 敏感类型 → 敏感级别（键为 {@link com.hdu.apisensitivities.entity.SensitiveType} 枚举名） */
    private Map<String, SensitiveLevel> typeLevels = new HashMap<>();

    /** 敏感级别 → 脱敏策略名（预留：可指定 L1/L2/L3 各自策略） */
    private Map<String, String> levelStrategy = new HashMap<>();

    /** 算法校验开关（脱敏结果二次明文残留扫描） */
    private boolean verifyEnabled = true;

    /** 人工复核开关（校验未通过的事件进入人工复核队列） */
    private boolean manualReviewEnabled = true;

    /** 算法校验覆盖率最低阈值（0~1），低于阈值触发人工复核 */
    private double coverageThreshold = 0.9;
}
