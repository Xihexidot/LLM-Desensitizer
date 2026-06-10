package com.hdu.apisensitivities.service.gateway;

import java.util.*;

/**
 * 统一风险评分器 — PluginAuditController 和 buildRiskDecision 共用。
 * 按敏感类型严重度加权：高危类型 3 分、中危 2 分、低危 1 分。
 */
public final class RiskScorer {

    private static final Set<String> HIGH_SEVERITY = Set.of(
            "ID_CARD", "BANK_CARD", "PASSWORD", "API_KEY",
            "PASSPORT", "SOCIAL_SECURITY", "CREDIT_CARD");
    private static final Set<String> MEDIUM_SEVERITY = Set.of(
            "PHONE_NUMBER", "EMAIL", "BIRTH_DATE", "LICENSE_PLATE");

    private RiskScorer() {
    }

    /**
     * 返回总分和对应风险等级。
     *
     * @param matchedTypes 匹配到的敏感类型名列表（如 ["PHONE_NUMBER","ID_CARD"]）
     * @param result       输出参数：result[0]=总分, result[1]=风险等级
     */
    public static void scoreWithLevel(List<String> matchedTypes, int[] result) {
        if (matchedTypes == null || matchedTypes.isEmpty()) {
            result[0] = 0;
            result[1] = 0; // NONE
            return;
        }
        int score = 0;
        for (String type : matchedTypes) {
            String upper = type != null ? type.toUpperCase() : "";
            if (HIGH_SEVERITY.contains(upper)) {
                score += 3;
            } else if (MEDIUM_SEVERITY.contains(upper)) {
                score += 2;
            } else {
                score += 1;
            }
        }
        result[0] = score;
        // riskLevel: 0=NONE, 1=LOW, 2=MEDIUM, 3=HIGH, 4=CRITICAL
        if (score >= 8)
            result[1] = 4; // CRITICAL
        else if (score >= 4)
            result[1] = 3; // HIGH
        else if (score >= 2)
            result[1] = 2; // MEDIUM
        else
            result[1] = 1; // LOW
    }

    /**
     * 将 riskLevel 整数转为 GatewayRiskLevel 枚举名。
     */
    public static String levelName(int riskLevel) {
        switch (riskLevel) {
            case 1:
                return "LOW";
            case 2:
                return "MEDIUM";
            case 3:
                return "HIGH";
            case 4:
                return "CRITICAL";
            default:
                return "NONE";
        }
    }
}
