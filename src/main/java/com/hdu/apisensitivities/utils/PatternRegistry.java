package com.hdu.apisensitivities.utils;

import com.hdu.apisensitivities.entity.SensitiveType;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/* 正则表达式规则集合 */
// TODO:  规则过窄导致漏报（手机号提取不全）
// 仅支持典型11位，缺少区分短码、座机、国际号、1800等变体。
// 没考虑输入中有中文或分隔符如138 1234 5678。

// 识别场景容易错误
// "联系我：1393250881@@qq.com"
// 脱敏后提示词
// 📋 复制
// "联系我：[PASSWORD].com"
public class PatternRegistry {

        private static final Map<SensitiveType, Pattern> PATTERN_MAP = new HashMap<>();

        static {
                // 中国手机号
                // 使用 lookbehind (?<!\d) 和 lookahead (?!\d) 确保手机号不是更长数字串的一部分
                PATTERN_MAP.put(SensitiveType.PHONE_NUMBER,
                                Pattern.compile("(?<!\\d)(?:\\+86)?1[3-9]\\d{9}(?!\\d)"));

                // 邮箱
                PATTERN_MAP.put(SensitiveType.EMAIL,
                                Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"));

                // 身份证号（中国）
                PATTERN_MAP.put(SensitiveType.ID_CARD,
                                Pattern.compile("\\b[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]\\b"));

                // 银行卡号
                PATTERN_MAP.put(SensitiveType.BANK_CARD,
                                Pattern.compile("\\b[1-9]\\d{12,18}\\b"));

                // API密钥（通用模式 - 配合熵值检测）
                // 匹配常见的Key格式，如 sk-xxx, AKIAxxx, 或者是32位以上的十六进制/Base64字符串
                PATTERN_MAP.put(SensitiveType.API_KEY,
                                Pattern.compile("(?i)\\b(?:sk-[a-zA-Z0-9]{20,}|AKIA[0-9A-Z]{16}|[a-f0-9]{32,}|[a-zA-Z0-9+/=]{30,})\\b"));

                // 信用卡号
                PATTERN_MAP.put(SensitiveType.CREDIT_CARD,
                                Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12}|(?:2131|1800|35\\d{3})\\d{11})\\b"));

                // 护照号
                PATTERN_MAP.put(SensitiveType.PASSPORT,
                                Pattern.compile("\\b[A-Z][0-9]{8}\\b|\\b[GgEe][0-9]{8}\\b"));

                // 社会安全码/社保号 (以US SSN为例，也可扩展中国社保)
                PATTERN_MAP.put(SensitiveType.SOCIAL_SECURITY,
                                Pattern.compile("\\b(?!000|666|9\\d{2})\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b"));

                // 出生日期 (YYYY-MM-DD, YYYY/MM/DD)
                PATTERN_MAP.put(SensitiveType.BIRTH_DATE,
                                Pattern.compile("\\b(?:19|20)\\d{2}[-/](?:0[1-9]|1[0-2])[-/](?:0[1-9]|[12]\\d|3[01])\\b"));

                // 密码 (潜在的密码字段值，通常结合上下文检测，这里提供一个匹配常见复杂字符串的宽泛正则)
                PATTERN_MAP.put(SensitiveType.PASSWORD,
                                Pattern.compile("\\b(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d@$!%*#?&]{8,}\\b"));

                // IP地址（IPv4 与常见 IPv6 表达）
                PATTERN_MAP.put(SensitiveType.IP_ADDRESS,
                                Pattern.compile(
                                                "\\b(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b"
                                                                + "|" +
                                                                "(?:(?:[A-Fa-f0-9]{1,4}:){7}[A-Fa-f0-9]{1,4}|(?:[A-Fa-f0-9]{1,4}:){1,7}:|:(?::[A-Fa-f0-9]{1,4}){1,7})"));

                // 车牌号（普通与新能源，更加宽松兼容实际文本）
                // 普通车牌：省份简称 + 字母 + 5位字母/数字（不含 I/O）
                // 新能源：省份简称 + 字母 + D/F + 5位字母/数字 或 5位字母/数字 + D/F
                PATTERN_MAP.put(SensitiveType.LICENSE_PLATE,
                                Pattern.compile(
                                                "(?:" +
                                                                "(?:[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙闽藏青川宁琼][A-Z][A-HJ-NP-Z0-9]{5})"
                                                                +
                                                                "|" +
                                                                "(?:[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙闽藏青川宁琼][A-Z](?:[DF][A-HJ-NP-Z0-9]{5}|[A-HJ-NP-Z0-9]{5}[DF]))"
                                                                +
                                                                ")"));
        }

        public static Pattern getPattern(SensitiveType type) {
                return PATTERN_MAP.get(type);
        }

        public static Map<SensitiveType, Pattern> getAllPatterns() {
                return new HashMap<>(PATTERN_MAP);
        }

        // 添加自定义模式
        public static void addCustomPattern(SensitiveType type, String regex) {
                PATTERN_MAP.put(type, Pattern.compile(regex));
        }
}
