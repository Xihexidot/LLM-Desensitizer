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
            case PASSWORD:
                if (!matchedText.matches(".*[A-Za-z].*") || !matchedText.matches(".*\\d.*")) {
                    return 0.0;
                }
                if (ValidationUtils.calculateEntropy(matchedText) < 2.5) {
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
                return Arrays.asList("api", "key", "secret", "token", "access", "sk", "ak", "密钥", "令牌");
            case PASSWORD:
                return Arrays.asList("password", "pwd", "pass", "secret", "密码", "口令", "登录");
            case EMAIL:
                return Arrays.asList("email", "mail", "邮箱", "邮件", "联系");
            default:
                return Arrays.asList();
        }
    }
}
