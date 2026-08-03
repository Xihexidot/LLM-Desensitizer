package com.hdu.apisensitivities.entity;

/**
 * 敏感信息分级（业务分级标准，用于分级分类脱敏）。
 * <p>
 * 分级依据：信息一旦泄露造成的危害程度与可恢复性。
 * <ul>
 * <li>{@link #HIGH}  高敏：唯一身份标识类，必须完全掩码（无任何信息残留）</li>
 * <li>{@link #MEDIUM} 中敏：联系/身份关联类，可采用部分脱敏（保留可辨识片段）</li>
 * <li>{@link #LOW}   低敏：属性/位置类，可采用泛化处理（保留统计价值）</li>
 * </ul>
 * </p>
 */
public enum SensitiveLevel {

    /** 高敏：完全掩码，例如身份证号、银行卡号、密码、API Key */
    HIGH(3, "高敏-完全掩码"),

    /** 中敏：部分脱敏，例如手机号、邮箱、人名 */
    MEDIUM(2, "中敏-部分脱敏"),

    /** 低敏：泛化处理，例如地址、机构、IP、车牌 */
    LOW(1, "低敏-泛化处理");

    private final int severity;

    private final String label;

    SensitiveLevel(int severity, String label) {
        this.severity = severity;
        this.label = label;
    }

    public int getSeverity() {
        return severity;
    }

    public String getLabel() {
        return label;
    }
}
