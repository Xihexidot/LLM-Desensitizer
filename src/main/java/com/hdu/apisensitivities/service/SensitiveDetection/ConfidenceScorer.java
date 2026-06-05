package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.utils.ValidationUtils;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class ConfidenceScorer {

    public double calculateConfidence(String fullText, int start, int end, String matchedText, SensitiveType type,
            boolean strictMode) {
        double baseConfidence = 0.9;
        if (strictMode) {
            baseConfidence = 0.5;
        }

        boolean formatValid = true;
        switch (type) {
            case PHONE_NUMBER:
                formatValid = ValidationUtils.validatePhoneNumber(matchedText);
                break;
            case ID_CARD:
                formatValid = ValidationUtils.validateIdCard(matchedText);
                break;
            case EMAIL:
                formatValid = ValidationUtils.validateEmail(matchedText);
                break;
            case LICENSE_PLATE:
                formatValid = ValidationUtils.validateLicensePlate(matchedText);
                break;
            case BANK_CARD:
                if (strictMode) {
                    formatValid = ValidationUtils.validateBankCard(matchedText);
                } else {
                    String normalized = matchedText.replaceAll("[\\s-]", "");
                    formatValid = normalized.matches("\\d{13,19}");
                }
                break;
            case API_KEY:
                // API_KEY 类型：要求较高熵值，最小长度16，不排除API Key格式（本身就是API Key）
                if (matchedText.length() < 16) {
                    return 0.3;
                }
                if (ValidationUtils.calculateEntropy(matchedText) < 3.0) {
                    return 0.3;
                }
                break;
            case PASSWORD:
                if (!matchedText.matches(".*[A-Za-z].*") || !matchedText.matches(".*\\d.*")) {
                    return 0.0;
                }
                if (ValidationUtils.calculateEntropy(matchedText) < 2.5) {
                    return 0.2;
                }
                // 排除常见非密码模式：API Key前缀 / JWT / 数据库连接串 / 订单号格式
                if (looksLikeApiKeyOrToken(matchedText)) {
                    return 0.0;
                }
                // 无密码上下文时提高要求：排除统一社会信用代码、工商注册号等结构化编码
                if (!checkContext(fullText, start, end, type)
                        && ValidationUtils.calculateEntropy(matchedText) < 3.5) {
                    return 0.2;
                }
                break;
            default:
                break;
        }

        if (!formatValid) {
            if (strictMode) {
                return 0.0;
            }
            boolean hasContext = checkContext(fullText, start, end, type);
            return hasContext ? 0.7 : 0.4;
        }

        boolean hasContext = checkContext(fullText, start, end, type);

        if (hasContext) {
            baseConfidence = Math.min(1.0, baseConfidence + 0.15);
        } else if (strictMode) {
            baseConfidence -= 0.1;
        }

        return Math.max(0.0, Math.min(1.0, baseConfidence));
    }

    public boolean checkContext(String text, int start, int end, SensitiveType type) {
        int contextRange = 20;
        int left = Math.max(0, start - contextRange);
        int right = Math.min(text.length(), end + contextRange);

        String surroundingText = text.substring(left, right).toLowerCase();
        List<String> keywords = getKeywordsForType(type);

        if (keywords.isEmpty())
            return false;

        for (String keyword : keywords) {
            if (surroundingText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public List<String> getKeywordsForType(SensitiveType type) {
        switch (type) {
            case PHONE_NUMBER:
                return Arrays.asList("tel", "phone", "mobile", "联系", "电话", "手机", "号码");
            case ID_CARD:
                return Arrays.asList("id", "card", "identity", "身份证", "证件", "号码");
            case BANK_CARD:
            case CREDIT_CARD:
                return Arrays.asList("bank", "card", "debit", "credit", "银行", "卡号", "账户", "支付");
            case API_KEY:
                return Arrays.asList("api", "key", "secret", "token", "access", "sk", "pk", "ak", "akia", "密钥", "令牌",
                        "apikey", "api_key");
            case PASSWORD:
                return Arrays.asList("password", "pwd", "pass", "secret", "密码", "口令", "登录");
            case EMAIL:
                return Arrays.asList("email", "mail", "邮箱", "邮件", "联系");
            default:
                return Arrays.asList();
        }
    }

    /**
     * 判断匹配文本是否更像是 API Key / Token / JWT / 数据库连接串，而非密码。
     */
    private static boolean looksLikeApiKeyOrToken(String text) {
        if (text == null || text.length() < 8)
            return false;
        String lower = text.toLowerCase();
        // API Key 前缀模式：sk- / pk- / AKIA / ASIA / AIza
        if (lower.startsWith("sk-") || lower.startsWith("pk-") || lower.startsWith("ak-")
                || lower.startsWith("akia") || lower.startsWith("asia")
                || lower.startsWith("aiza")) {
            return true;
        }
        // JWT 三段式：含有两个点的 base64 串
        if (text.chars().filter(c -> c == '.').count() == 2 && text.length() > 30) {
            return true;
        }
        // 数据库连接串：jdbc: / mysql: / mongodb: / postgres:
        if (lower.contains("jdbc:") || lower.contains("mysql:") || lower.contains("mongodb:")) {
            return true;
        }
        // 带 service/region 前缀的常见 token 格式
        if (lower.matches("^[a-z]{2,10}-[a-z]{2,15}-.*")) {
            return true;
        }
        // 订单号标准格式：大写字母+连字符+数字连字符 (如 ORD-2024-0615-8823)
        if (text.matches("^[A-Z]{2,5}-\\d{4}-\\d{4}-\\d{4,}$")) {
            return true;
        }
        return false;
    }
}
