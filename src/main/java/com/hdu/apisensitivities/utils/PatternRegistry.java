package com.hdu.apisensitivities.utils;

import com.hdu.apisensitivities.entity.SensitiveType;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class PatternRegistry {

        private static final Map<SensitiveType, Pattern> PATTERN_MAP = new HashMap<>();

        static {
                // 1. 手机号 + 座机号
                // 手机：兼容空格/横杠：138-1234-5678 或 138 1234 5678
                // 座机：010-12345678、021-87654321、0755-12345678（区号3-4位 + 号码7-8位）
                PATTERN_MAP.put(SensitiveType.PHONE_NUMBER,
                                Pattern.compile("(?<!\\d)(?:(?:\\+86)?1[3-9]\\d{1}([\\s-]?\\d{4}){2}|0\\d{2,3}[\\s-]?\\d{7,8})(?!\\d)"));

                // 2. 邮箱：优化结尾匹配，防止截断，并确保后缀完整性
                PATTERN_MAP.put(SensitiveType.EMAIL,
                                Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?:\\.[A-Za-z]{2,})?"));

                // 3. 银行卡号：13-19 位纯数字（或含空格/横杠），前后不能有数字
                // 策略：先匹配连续 13-19 位数字，或带分隔符的 4 位组格式
                PATTERN_MAP.put(SensitiveType.BANK_CARD,
                                Pattern.compile("(?<!\\d)[1-9](?:\\d{12,18}|\\d{3}(?:[\\s-]?\\d{4}){2,3}[\\s-]?\\d{0,4})(?!\\d)"));

                // 4. 身份证号：严格 18 位格式锁定
                PATTERN_MAP.put(SensitiveType.ID_CARD,
                                Pattern.compile("(?<!\\d)[1-9]\\d{5}\\s*(?:18|19|20)\\d{2}\\s*(?:0[1-9]|1[0-2])\\s*(?:0[1-9]|[12]\\d|3[01])\\s*\\d{3}[0-9Xx](?!\\d)"));

                // 5. 密码 / 密钥（扩展字符类覆盖常见特殊符号，必须同时包含字母和数字，长度 8-64）
                PATTERN_MAP.put(SensitiveType.PASSWORD,
                                Pattern.compile("(?<![A-Za-z0-9])(?=[A-Za-z\\d@$!%*#?&\\-_/+.]{0,63}[A-Za-z])(?=[A-Za-z\\d@$!%*#?&\\-_/+.]{0,63}\\d)[A-Za-z\\d@$!%*#?&\\-_/+.]{8,64}(?![A-Za-z0-9])"));

                // 5b. API 密钥 / Token：特定前缀模式
                // 覆盖 OpenAI(sk-/pk-)、AWS AKIA/ASIA、Google AIza 等常见格式
                PATTERN_MAP.put(SensitiveType.API_KEY,
                                Pattern.compile(
                                                // OpenAI / 类OpenAI: sk-xxx, pk-xxx (至少20位)
                                                "(?<![A-Za-z0-9])(?i)(sk|pk|ak)-[A-Za-z0-9_\\-]{20,}(?![A-Za-z0-9])" +
                                                                "|" +
                                                                // AWS Access Key ID: AKIA + 16位大写字母数字
                                                                "AKIA[A-Z0-9]{16}" +
                                                                "|" +
                                                                // AWS Temporary Access Key: ASIA + 16位
                                                                "ASIA[A-Z0-9]{16}" +
                                                                "|" +
                                                                // Google API Key: AIza + 35位
                                                                "AIza[A-Za-z0-9_\\-]{35}" +
                                                                "|" +
                                                                // JWT Token: 三段式 base64url (header.payload.signature)
                                                                "(?<![A-Za-z0-9_\\-.])[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(?![A-Za-z0-9_\\-.])"));

                // 6. 护照
                PATTERN_MAP.put(SensitiveType.PASSPORT,
                                Pattern.compile("(?<![A-Z0-9])[EeGg][0-9]{8}(?![0-9])"));
                // 信用卡号
                PATTERN_MAP.put(SensitiveType.CREDIT_CARD,
                                Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12}|(?:2131|1800|35\\d{3})\\d{11})\\b"));

                // 社会安全码/社保号 (以US SSN为例，也可扩展中国社保)
                PATTERN_MAP.put(SensitiveType.SOCIAL_SECURITY,
                                Pattern.compile("\\b(?!000|666|9\\d{2})\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b"));

                // 出生日期 (YYYY-MM-DD, YYYY/MM/DD)
                PATTERN_MAP.put(SensitiveType.BIRTH_DATE,
                                Pattern.compile("\\b(?:19|20)\\d{2}[-/](?:0[1-9]|1[0-2])[-/](?:0[1-9]|[12]\\d|3[01])\\b"));

                // IP地址（IPv4 与常见 IPv6 表达）
                PATTERN_MAP.put(SensitiveType.IP_ADDRESS,
                                Pattern.compile(
                                                "\\b(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b"
                                                                + "|" +
                                                                "(?:(?:[A-Fa-f0-9]{1,4}:){7}[A-Fa-f0-9]{1,4}|(?:[A-Fa-f0-9]{1,4}:){1,7}:|:(?::[A-Fa-f0-9]{1,4}){1,7})"));
                // 车牌号（普通与新能源，兼容·分隔符如京A·88888、京A·D12345）
                PATTERN_MAP.put(SensitiveType.LICENSE_PLATE,
                                Pattern.compile(
                                                "(?<![A-Z0-9])" + // 前面不是字母数字
                                                                "[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙闽藏青川宁琼使领]" +
                                                                "[A-Z]" +
                                                                "[·]?" + // 可选分隔符
                                                                "[A-Z]?" + // 可选的新能源标识字母
                                                                "[A-Z0-9]{5,6}" + // 5-6位
                                                                "(?![A-Z0-9])" // 后面不是字母数字
                                ));
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

        // 覆盖内置模式（与 addCustomPattern 行为相同，语义明确）
        public static void overridePattern(SensitiveType type, String regex) {
                PATTERN_MAP.put(type, Pattern.compile(regex));
        }
}
