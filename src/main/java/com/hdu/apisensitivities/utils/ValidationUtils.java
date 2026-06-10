package com.hdu.apisensitivities.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * 验证工具类，封装各种格式校验和信息熵计算，供检测服务复用。
 */
public final class ValidationUtils {

    private ValidationUtils() {
        // 工具类私有构造
    }

    public static double calculateEntropy(String s) {
        if (s == null || s.isEmpty())
            return 0.0;

        Map<Character, Integer> frequency = new HashMap<>();
        for (char c : s.toCharArray()) {
            frequency.put(c, frequency.getOrDefault(c, 0) + 1);
        }

        double entropy = 0.0;
        int len = s.length();
        for (int count : frequency.values()) {
            double prob = (double) count / len;
            entropy -= prob * (Math.log(prob) / Math.log(2));
        }

        return entropy;
    }

    public static boolean validateBankCard(String bankCard) {
        if (bankCard == null) {
            return false;
        }
        String normalized = bankCard.replaceAll("[\\s-]", "");
        if (normalized.length() < 13 || normalized.length() > 19 || !normalized.matches("\\d+")) {
            return false;
        }
        int sum = 0;
        boolean alternate = false;
        for (int i = normalized.length() - 1; i >= 0; i--) {
            int n = Integer.parseInt(normalized.substring(i, i + 1));
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n = (n % 10) + 1;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }

    public static boolean validatePhoneNumber(String phone) {
        if (phone == null) {
            return false;
        }
        String normalized = phone.replaceAll("[\\s-]", "");
        return normalized.matches("1[3-9]\\d{9}") || normalized.matches("0\\d{2,3}\\d{7,8}");
    }

    public static boolean validateIdCard(String idCard) {
        if (idCard == null || idCard.length() != 18) {
            return false;
        }

        char[] chars = idCard.toCharArray();
        int[] coefficient = { 7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2 };
        char[] checkCodes = { '1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2' };

        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (chars[i] - '0') * coefficient[i];
        }

        char checkCode = checkCodes[sum % 11];
        return chars[17] == checkCode || (Character.toUpperCase(chars[17]) == checkCode);
    }

    public static boolean validateEmail(String email) {
        return email != null && email.contains("@") && email.contains(".");
    }

    public static boolean validateLicensePlate(String plate) {
        if (plate == null) {
            return false;
        }
        // 兼容京A88888、京A·88888、京AD12345、京A·D12345 等格式
        String regex = "(?:(?:[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙闽藏青川宁琼港澳]" +
                "[A-Z][·]?[A-Z]?[A-HJ-NP-Z0-9]{4}[A-HJ-NP-Z0-9挂学警港澳])|" +
                "(?:[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙闽藏青川宁琼]" +
                "[A-Z][·]?(?:[DF][A-HJ-NP-Z0-9]{5}|[A-HJ-NP-Z0-9]{5}[DF])))";
        return plate.matches(regex);
    }
}
