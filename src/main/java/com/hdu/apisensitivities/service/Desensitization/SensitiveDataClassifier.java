package com.hdu.apisensitivities.service.Desensitization;

import com.hdu.apisensitivities.config.DesensitizationRuleProperties;
import com.hdu.apisensitivities.entity.SensitiveLevel;
import com.hdu.apisensitivities.entity.SensitiveType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 敏感数据分级分类器：将 {@link SensitiveType} 映射为 {@link SensitiveLevel}。
 * <p>
 * 分级依据（业务标准）：
 * <ul>
 * <li>{@link SensitiveLevel#HIGH}（完全掩码）：唯一身份标识类，一旦泄露无法恢复，
 *     例如身份证号、银行卡号、护照号、社保号、密码、API Key</li>
 * <li>{@link SensitiveLevel#MEDIUM}（部分脱敏）：联系/身份关联类，泄露后关联到具体个人，
 *     例如手机号、邮箱、人名</li>
 * <li>{@link SensitiveLevel#LOW}（泛化处理）：属性/位置类，泄露风险相对可控且需保留统计价值，
 *     例如地址、机构、IP、车牌</li>
 * </ul>
 * 默认映射可被 {@code desensitization.rule.type-levels[类型]=级别} 动态覆盖（无需改代码）。
 * </p>
 */
@Component
public class SensitiveDataClassifier {

    private final DesensitizationRuleProperties ruleProperties;

    private final Map<SensitiveType, SensitiveLevel> defaultLevels = new EnumMap<>(SensitiveType.class);

    public SensitiveDataClassifier(DesensitizationRuleProperties ruleProperties) {
        this.ruleProperties = ruleProperties;
        buildDefaultLevels();
    }

    private void buildDefaultLevels() {
        // 高敏：唯一身份标识，完全掩码
        for (SensitiveType type : new SensitiveType[] {
                SensitiveType.ID_CARD, SensitiveType.BANK_CARD, SensitiveType.CREDIT_CARD,
                SensitiveType.PASSPORT, SensitiveType.SOCIAL_SECURITY,
                SensitiveType.PASSWORD, SensitiveType.API_KEY }) {
            defaultLevels.put(type, SensitiveLevel.HIGH);
        }
        // 中敏：联系/身份关联，部分脱敏
        for (SensitiveType type : new SensitiveType[] {
                SensitiveType.PHONE_NUMBER, SensitiveType.EMAIL,
                SensitiveType.NAME, SensitiveType.PERSON, SensitiveType.BIRTH_DATE }) {
            defaultLevels.put(type, SensitiveLevel.MEDIUM);
        }
        // 低敏：属性/位置，泛化处理
        for (SensitiveType type : new SensitiveType[] {
                SensitiveType.ADDRESS, SensitiveType.ORGANIZATION,
                SensitiveType.IP_ADDRESS, SensitiveType.LICENSE_PLATE, SensitiveType.CUSTOM }) {
            defaultLevels.put(type, SensitiveLevel.LOW);
        }
    }

    /**
     * 对敏感类型进行分级。优先级：动态规则覆盖 > 默认映射 > 兜底 LOW。
     *
     * @param type 敏感类型
     * @return 对应的敏感级别（永不为 null）
     */
    public SensitiveLevel classify(SensitiveType type) {
        if (type == null) {
            return SensitiveLevel.LOW;
        }
        SensitiveLevel override = ruleProperties.getTypeLevels().get(type.name());
        if (override != null) {
            return override;
        }
        return defaultLevels.getOrDefault(type, SensitiveLevel.LOW);
    }

    /** 获取某级别的默认策略名（当前为预置映射，后续可由 {@code level-strategy} 动态指定） */
    public String strategyNameForLevel(SensitiveLevel level) {
        if (level == null) {
            return "generalizationDesensitizationStrategy";
        }
        String configured = ruleProperties.getLevelStrategy().get(level.name());
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return switch (level) {
            case HIGH -> "maskDesensitizationStrategy";
            case MEDIUM -> "partialDesensitizationStrategy";
            case LOW -> "generalizationDesensitizationStrategy";
        };
    }
}
