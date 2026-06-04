package com.hdu.apisensitivities.utils;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.Segment;
import com.hankcs.hanlp.seg.common.Term;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NlpEntityDetector {

    // 启用人名、地名、机构名识别
    private static final Segment segment = HanLP.newSegment()
            .enableNameRecognize(true) // 开启人名识别
            .enablePlaceRecognize(true) // 开启地名识别
            .enableOrganizationRecognize(true); // 开启机构名识别

    private static final Set<String> COMMON_SURNAME_PREFIX = new HashSet<>();
    private static final Set<String> ADDRESS_SUFFIXES = new HashSet<>();

    /**
     * 人名回退匹配：姓氏白名单 + 1~3 个汉字。
     * 前后放开中文限制，通过后缀字符类减法过滤：
     * (?![\\u4e00-\\u9fa5&&[^的之于与和以所]]) → 允许后跟 "的/之/于/与/和/以/所" 等助词，
     * 但阻止后跟其他中文字符（防止"纸张质""魏欣怡身份证"等嵌入复合词）。
     */
    private static final Pattern NAME_FALLBACK_PATTERN = Pattern.compile(
            "([赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳酆鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧][\\u4e00-\\u9fa5]{1,3})(?![\\u4e00-\\u9fa5&&[^的之于与和以所]])");

    /**
     * 姓氏+汉字组合恰好形成非人名常用词的黑名单。
     * 放开负向后顾后，仅此少数词可能被误认为人名。
     */
    private static final Set<String> NON_PERSON_WORDS = new HashSet<>();
    static {
        String[] words = {
                // 张: 纸张/张扬(作动词)/张望/张罗/张力(物理)/张开/张狂
                "纸张", "张望", "张罗", "张力", "张开", "张狂",
                // 马: 马虎/马上/马力(功率)/马匹/马桶
                "马虎", "马上", "马力", "马匹", "马桶", "马虎眼",
                // 王: 王国/王牌/王法/王位/王冠/王权
                "王国", "王牌", "王法", "王位", "王冠", "王权",
                // 李: 李子/李树
                "李子", "李树",
                // 周: 周围/周年/周期
                "周围", "周年", "周期",
                // 韩: 韩国
                "韩国",
                // 朝代名(非人名)
                "唐朝", "宋朝", "秦汉", "魏晋", "元朝",
                // 郑: 郑重
                "郑重",
                // 其他
                "张扬"
        };
        for (String w : words) {
            NON_PERSON_WORDS.add(w);
        }
    }
    private static final Pattern ADDRESS_FALLBACK_PATTERN = Pattern.compile(
            "(?<![\\u4e00-\\u9fa5])([\\u4e00-\\u9fa5]{2,6}(?:路|街|巷|道|大道|区|市|县|镇|村|号))");

    /**
     * 常见非地址标签词黑名单（以地址后缀结尾但实为标识符/标签）。
     * 例如：身份证号、银行卡号、账号、编号 等。
     */
    private static final Set<String> NON_ADDRESS_LABELS = new HashSet<>();
    static {
        String[] labels = {
                "身份证号", "银行卡号", "信用卡号", "账号", "编号", "流水号",
                "订单号", "学号", "工号", "座号", "序号", "型号", "牌号",
                "证号", "卡号", "票号", "单号", "档号"
        };
        for (String label : labels) {
            NON_ADDRESS_LABELS.add(label);
        }
    }

    // 非地址标签的关键词片段（出现在以"号"结尾的词中则为非地址）
    private static final Set<String> NON_ADDRESS_PREFIX_CHARS = new HashSet<>();
    static {
        String[] chars = { "证", "卡", "账", "编", "票", "单", "学", "工", "座", "序", "型", "牌", "档" };
        for (String c : chars) {
            NON_ADDRESS_PREFIX_CHARS.add(c);
        }
    }

    public static List<SensitiveEntity> detect(String text) {
        List<SensitiveEntity> entities = new ArrayList<>();
        List<Term> termList = segment.seg(text);

        System.out.println("HanLP输出: " + termList.stream()
                .map(t -> t.word + "/" + t.nature)
                .collect(Collectors.joining(", ")));

        int currentPos = 0;
        for (Term term : termList) {
            String word = term.word;
            String nature = term.nature.toString();

            if (word == null || word.trim().isEmpty()) {
                currentPos += word != null ? word.length() : 0;
                continue;
            }

            SensitiveType type = getTypeByNature(nature, word);
            if (type == null) {
                if (isPotentialPersonName(word)) {
                    type = SensitiveType.PERSON;
                } else if (isPotentialAddress(word)) {
                    type = SensitiveType.ADDRESS;
                }
            }

            if (type != null && word != null && !word.trim().isEmpty()) {
                // 查找该词在原文本中的偏移量（注意处理重复词）
                int start = text.indexOf(word, currentPos);
                if (start >= 0) {
                    entities.add(SensitiveEntity.builder()
                            .type(type)
                            .originalText(word)
                            .start(start)
                            .end(start + word.length())
                            .confidence(adjustConfidence(type, word))
                            .build());
                    currentPos = start + word.length();
                } else {
                    currentPos += word.length();
                }
            } else {
                currentPos += word.length();
            }
        }
        addFallbackEntities(text, entities);
        return entities;
    }

    private static SensitiveType getTypeByNature(String nature, String word) {
        if (nature == null || word == null || word.trim().isEmpty()) {
            return null;
        }
        switch (nature) {
            case "nr":
            case "nr1":
            case "nr2":
            case "nrj":
            case "nrf":
                return SensitiveType.PERSON;
            case "ns":
            case "nsf":
            case "nz":
                return SensitiveType.ADDRESS;
            case "nt":
                return SensitiveType.ORGANIZATION;
            default:
                return null;
        }
    }

    private static boolean isPotentialPersonName(String word) {
        if (word == null) {
            return false;
        }
        String trimmed = word.trim();
        if (trimmed.length() < 2 || trimmed.length() > 4) {
            return false;
        }
        if (!trimmed.matches("^[\u4e00-\u9fa5]+$")) {
            return false;
        }
        return COMMON_SURNAME_PREFIX.contains(trimmed.substring(0, 1));
    }

    private static boolean isPotentialAddress(String word) {
        if (word == null) {
            return false;
        }
        String trimmed = word.trim();
        if (trimmed.length() < 2 || trimmed.length() > 12) {
            return false;
        }
        if (!trimmed.matches("^[\u4e00-\u9fa50-9]+$")) {
            return false;
        }
        String lastChar = trimmed.substring(trimmed.length() - 1);
        return ADDRESS_SUFFIXES.contains(lastChar);
    }

    private static double adjustConfidence(SensitiveType type, String word) {
        if (type == SensitiveType.PERSON && isPotentialPersonName(word)) {
            return 0.85;
        }
        if (type == SensitiveType.ADDRESS && isPotentialAddress(word)) {
            return 0.80;
        }
        return 0.75;
    }

    private static void addFallbackEntities(String text, List<SensitiveEntity> entities) {
        Set<String> existingKeys = new HashSet<>();
        for (SensitiveEntity entity : entities) {
            existingKeys.add(buildEntityKey(entity));
        }

        addFallbackMatches(text, NAME_FALLBACK_PATTERN, SensitiveType.PERSON, entities, existingKeys);
        addFallbackMatches(text, ADDRESS_FALLBACK_PATTERN, SensitiveType.ADDRESS, entities, existingKeys);
    }

    private static void addFallbackMatches(String text, Pattern pattern, SensitiveType type,
            List<SensitiveEntity> entities, Set<String> existingKeys) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String word = matcher.group(1);
            int start = matcher.start(1);
            int end = matcher.end(1);
            String key = type.name() + ":" + start + ":" + end;
            if (existingKeys.contains(key)) {
                continue;
            }
            String trimmed = word.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // ADDRESS 回退匹配：过滤非地址标签词（如"身份证号""银行卡号"）
            if (type == SensitiveType.ADDRESS && isNonAddressLabel(trimmed)) {
                continue;
            }
            // PERSON 回退匹配：过滤非人名常用词（如"纸张""马虎"）
            if (type == SensitiveType.PERSON && NON_PERSON_WORDS.contains(trimmed)) {
                continue;
            }
            entities.add(SensitiveEntity.builder()
                    .type(type)
                    .originalText(trimmed)
                    .start(start)
                    .end(end)
                    .confidence(type == SensitiveType.PERSON ? 0.75 : 0.70)
                    .build());
            existingKeys.add(key);
        }
    }

    /**
     * 判断一个以地址后缀结尾的词是否为非地址标签（如"身份证号""银行卡号""账号"等）。
     */
    private static boolean isNonAddressLabel(String word) {
        if (word == null || word.length() < 2) {
            return false;
        }
        // 精确黑名单匹配
        if (NON_ADDRESS_LABELS.contains(word)) {
            return true;
        }
        // 以"号"结尾时，检查倒数第二个字是否为非地址关键词（证、卡、账、编 等）
        if (word.endsWith("号") && word.length() >= 2) {
            String secondLast = word.substring(word.length() - 2, word.length() - 1);
            return NON_ADDRESS_PREFIX_CHARS.contains(secondLast);
        }
        return false;
    }

    private static String buildEntityKey(SensitiveEntity entity) {
        return entity.getType().name() + ":" + entity.getStart() + ":" + entity.getEnd();
    }
}
