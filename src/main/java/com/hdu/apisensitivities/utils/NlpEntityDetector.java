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
            .enableNameRecognize(true)
            .enablePlaceRecognize(true)
            .enableOrganizationRecognize(true);

    // ======================== 可配置词典（volatile 支持运行时替换） ========================
    private static volatile Set<String> surnameWhitelist = initSurnameWhitelist();
    private static volatile Set<String> personBlacklist = initPersonBlacklist();
    private static volatile Set<String> addressBlacklistLabels = initAddressBlacklistLabels();
    private static volatile Set<String> addressBlacklistPrefixChars = initAddressBlacklistPrefixChars();
    private static volatile Set<String> addressSuffixes = initAddressSuffixes();

    // ======================== 默认值初始化（代码兜底） ========================
    private static Set<String> initSurnameWhitelist() {
        Set<String> set = new HashSet<>();
        String[] surnames = {
                "赵", "钱", "孙", "李", "周", "吴", "郑", "王", "冯", "陈", "褚", "卫", "蒋", "沈", "韩", "杨",
                "朱", "秦", "尤", "许", "何", "吕", "施", "张", "孔", "曹", "严", "华", "金", "魏", "陶", "姜",
                "戚", "谢", "邹", "喻", "柏", "水", "窦", "章", "云", "苏", "潘", "葛", "奚", "范", "彭", "郎",
                "鲁", "韦", "昌", "马", "苗", "凤", "花", "方", "俞", "任", "袁", "柳", "酆", "鲍", "史", "唐",
                "费", "廉", "岑", "薛", "雷", "贺", "倪", "汤", "滕", "殷", "罗", "毕", "郝", "邬", "安", "常",
                "乐", "于", "时", "傅", "皮", "卞", "齐", "康", "伍", "余", "元", "卜", "顾", "孟", "平", "黄",
                "和", "穆", "萧", "尹", "姚", "邵", "湛", "汪", "祁", "毛", "禹", "狄", "米", "贝", "明", "臧",
                "计", "伏", "成", "戴", "谈", "宋", "茅", "庞", "熊", "纪", "舒", "屈", "项", "祝", "董", "梁",
                "杜", "阮", "蓝", "闵", "席", "季", "麻", "强", "贾", "路", "娄", "危", "江", "童", "颜", "郭",
                "梅", "盛", "林", "刁", "钟", "徐", "邱", "骆", "高", "夏", "蔡", "田", "樊", "胡", "凌", "霍",
                "虞", "万", "支", "柯", "昝", "管", "卢", "莫", "经", "房", "裘", "缪", "干", "解", "应", "宗",
                "丁", "宣", "贲", "邓", "郁", "单", "杭", "洪", "包", "诸", "左", "石", "崔", "吉", "钮", "龚",
                "程", "嵇", "邢", "滑", "裴", "陆", "荣", "翁", "荀", "羊", "於", "惠", "甄", "曲", "家", "封",
                "芮", "羿", "储", "靳", "汲", "邴", "糜", "松", "井", "段", "富", "巫", "乌", "焦", "巴", "弓",
                "牧", "隗", "山", "谷", "车", "侯", "宓", "蓬", "全", "郗", "班", "仰", "秋", "仲", "伊", "宫",
                "宁", "仇", "栾", "暴", "甘", "钭", "厉", "戎", "祖", "武", "符", "刘", "景", "詹", "束", "龙",
                "叶", "幸", "司", "韶", "郜", "黎", "蓟", "薄", "印", "白", "怀", "蒲", "邰", "从", "鄂", "索",
                "咸", "籍", "赖", "卓", "蔺", "屠", "蒙", "池", "乔", "阴", "鬱", "胥", "能", "苍", "双", "闻",
                "莘", "党", "翟", "谭", "贡", "劳", "逄", "姬", "申", "扶", "堵", "冉", "宰", "郦", "雍", "卻",
                "璩", "桑", "桂", "濮", "牛", "寿", "通", "边", "扈", "燕", "冀", "郏", "浦", "尚", "农", "温",
                "别", "庄", "晏", "柴", "瞿", "阎", "充", "慕", "连", "茹", "习", "宦", "艾", "鱼", "容", "向",
                "古", "易", "慎", "戈", "廖", "庾", "终", "暨", "居", "衡", "步", "都", "耿", "满", "弘", "匡",
                "国", "文", "寇", "广", "禄", "阙", "东", "欧", "殳", "沃", "利", "蔚", "越", "夔", "隆", "师",
                "巩", "厍", "聂", "晁", "勾", "敖", "融", "冷", "訾", "辛", "阚", "那", "简", "饶", "空", "曾",
                "毋", "沙", "乜", "养", "鞠", "须", "丰", "巢", "关", "蒯", "相", "查", "后", "荆", "红", "游",
                "竺", "权", "逯", "盖", "益", "桓", "公", "万俟", "司马", "上官", "欧阳", "夏侯", "诸葛",
                "闻人", "东方", "赫连", "皇甫", "尉迟", "公羊", "澹台", "公冶", "宗政", "濮阳", "淳于",
                "单于", "太叔", "申屠", "公孙", "仲孙", "轩辕", "令狐", "钟离", "宇文", "长孙", "慕容",
                "鲜于", "闾丘", "司徒", "司空", "丌官", "司寇", "仉", "督", "子车", "颛孙", "端木", "巫马",
                "公西", "漆雕", "乐正", "壤驷", "公良", "拓跋", "夹谷", "宰父", "谷梁", "晋", "楚", "闫",
                "法", "汝", "鄢", "涂", "钦", "段干", "百里", "东郭", "南门", "呼延", "归", "海", "羊舌",
                "微生", "岳", "帅", "缑", "亢", "况", "后", "有", "琴", "梁丘", "左丘", "东门", "西门",
                "商", "牟", "佘", "佴", "伯", "赏", "南宫", "墨", "哈", "谯", "笪", "年", "爱", "阳", "佟"
        };
        for (String s : surnames) {
            set.add(s);
        }
        return set;
    }

    private static Set<String> initPersonBlacklist() {
        Set<String> set = new HashSet<>();
        String[] words = {
                "纸张", "张望", "张罗", "张力", "张开", "张狂",
                "马虎", "马上", "马力", "马匹", "马桶", "马虎眼",
                "王国", "王牌", "王法", "王位", "王冠", "王权",
                "李子", "李树", "周围", "周年", "周期", "韩国",
                "唐朝", "宋朝", "秦汉", "魏晋", "元朝", "郑重", "张扬"
        };
        for (String w : words) {
            set.add(w);
        }
        return set;
    }

    private static Set<String> initAddressBlacklistLabels() {
        Set<String> set = new HashSet<>();
        String[] labels = {
                "身份证号", "银行卡号", "信用卡号", "账号", "编号", "流水号",
                "订单号", "学号", "工号", "座号", "序号", "型号", "牌号",
                "证号", "卡号", "票号", "单号", "档号"
        };
        for (String l : labels) {
            set.add(l);
        }
        return set;
    }

    private static Set<String> initAddressBlacklistPrefixChars() {
        Set<String> set = new HashSet<>();
        String[] chars = { "证", "卡", "账", "编", "票", "单", "学", "工", "座", "序", "型", "牌", "档" };
        for (String c : chars) {
            set.add(c);
        }
        return set;
    }

    private static Set<String> initAddressSuffixes() {
        Set<String> set = new HashSet<>();
        String[] suffixes = { "路", "街", "巷", "道", "区", "市", "县", "镇", "村", "号", "弄", "里", "园", "苑", "楼", "层" };
        for (String s : suffixes) {
            set.add(s);
        }
        return set;
    }

    // ======================== 外部配置入口 ========================
    public static void configure(Set<String> newSurnameWhitelist,
            Set<String> newPersonBlacklist,
            Set<String> newAddressBlacklistLabels,
            Set<String> newAddressBlacklistPrefixChars,
            Set<String> newAddressSuffixes) {
        if (newSurnameWhitelist != null)
            surnameWhitelist = newSurnameWhitelist;
        if (newPersonBlacklist != null)
            personBlacklist = newPersonBlacklist;
        if (newAddressBlacklistLabels != null)
            addressBlacklistLabels = newAddressBlacklistLabels;
        if (newAddressBlacklistPrefixChars != null)
            addressBlacklistPrefixChars = newAddressBlacklistPrefixChars;
        if (newAddressSuffixes != null)
            addressSuffixes = newAddressSuffixes;
    }

    // ======================== 默认值导出（供 DictConfigService 合并）
    // ========================
    public static Set<String> getDefaultSurnameWhitelist() {
        return initSurnameWhitelist();
    }

    public static Set<String> getDefaultPersonBlacklist() {
        return initPersonBlacklist();
    }

    public static Set<String> getDefaultAddressBlacklistLabels() {
        return initAddressBlacklistLabels();
    }

    public static Set<String> getDefaultAddressBlacklistPrefixChars() {
        return initAddressBlacklistPrefixChars();
    }

    public static Set<String> getDefaultAddressSuffixes() {
        return initAddressSuffixes();
    }

    // ======================== 正则 ========================

    /**
     * 人名回退匹配：姓氏白名单 + 1~3 个汉字。
     * 后缀字符类减法：(?![\\u4e00-\\u9fa5&&[^的之于与和以所]])
     * 允许后跟 "的/之/于/与/和/以/所" 等助词，但阻止后跟其他中文（防止嵌入复合词）。
     */
    private static final Pattern NAME_FALLBACK_PATTERN = Pattern.compile(
            "([赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳酆鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧][\\u4e00-\\u9fa5]{1,3})(?![\\u4e00-\\u9fa5&&[^的之于与和以所]])");

    private static final Pattern ADDRESS_FALLBACK_PATTERN = Pattern.compile(
            "(?<![\\u4e00-\\u9fa5])([\\u4e00-\\u9fa5]{2,6}(?:路|街|巷|道|大道|区|市|县|镇|村|号))");

    // ======================== 检测入口 ========================

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
        return surnameWhitelist.contains(trimmed.substring(0, 1));
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
        return addressSuffixes.contains(lastChar);
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
            if (type == SensitiveType.PERSON && personBlacklist.contains(trimmed)) {
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
        if (addressBlacklistLabels.contains(word)) {
            return true;
        }
        // 以"号"结尾时，检查倒数第二个字是否为非地址关键词（证、卡、账、编 等）
        if (word.endsWith("号") && word.length() >= 2) {
            String secondLast = word.substring(word.length() - 2, word.length() - 1);
            return addressBlacklistPrefixChars.contains(secondLast);
        }
        return false;
    }

    private static String buildEntityKey(SensitiveEntity entity) {
        return entity.getType().name() + ":" + entity.getStart() + ":" + entity.getEnd();
    }
}
