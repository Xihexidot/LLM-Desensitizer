package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.dto.SensitiveDict;
import com.hdu.apisensitivities.dto.SensitiveRule;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.mapper.SensitiveDictMapper;
import com.hdu.apisensitivities.mapper.SensitiveRuleMapper;
import com.hdu.apisensitivities.utils.NlpEntityDetector;
import com.hdu.apisensitivities.utils.PatternRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 字典与规则配置服务。
 * 启动时从 DB 加载词典（白名单/黑名单）注入 NlpEntityDetector，
 * 并从 DB 加载 BUILTIN 规则覆盖 PatternRegistry 代码默认值。
 */
@Slf4j
@Service
public class DictConfigService {

    private final SensitiveDictMapper dictMapper;
    private final SensitiveRuleMapper ruleMapper;
    private final CustomPatternDetectionService customPatternService;

    private final Map<String, Set<String>> dictCache = new HashMap<>();

    public DictConfigService(SensitiveDictMapper dictMapper,
            SensitiveRuleMapper ruleMapper,
            CustomPatternDetectionService customPatternService) {
        this.dictMapper = dictMapper;
        this.ruleMapper = ruleMapper;
        this.customPatternService = customPatternService;
    }

    @PostConstruct
    public void init() {
        reloadDicts();
        reloadBuiltinRules();
    }

    // ======================== 词典管理 ========================

    /**
     * 从 DB 重新加载全部启用词典，合并注入 NlpEntityDetector。
     */
    public synchronized void reloadDicts() {
        List<SensitiveDict> allDicts = dictMapper.selectAll();
        Map<String, List<SensitiveDict>> grouped = allDicts.stream()
                .collect(Collectors.groupingBy(SensitiveDict::getDictType));

        Set<String> surnameWhitelist = mergeDict(grouped, "SURNAME_WHITELIST",
                NlpEntityDetector.getDefaultSurnameWhitelist());
        Set<String> personBlacklist = mergeDict(grouped, "PERSON_BLACKLIST",
                NlpEntityDetector.getDefaultPersonBlacklist());
        Set<String> addressBlacklistLabels = mergeDict(grouped, "ADDRESS_BLACKLIST",
                NlpEntityDetector.getDefaultAddressBlacklistLabels());
        Set<String> addressBlacklistPrefix = mergeDict(grouped, "ADDRESS_BLACKLIST_PREFIX",
                NlpEntityDetector.getDefaultAddressBlacklistPrefixChars());
        Set<String> addressSuffixes = mergeDict(grouped, "ADDRESS_SUFFIXES",
                NlpEntityDetector.getDefaultAddressSuffixes());

        NlpEntityDetector.configure(
                surnameWhitelist,
                personBlacklist,
                addressBlacklistLabels,
                addressBlacklistPrefix,
                addressSuffixes);

        dictCache.put("SURNAME_WHITELIST", surnameWhitelist);
        dictCache.put("PERSON_BLACKLIST", personBlacklist);
        dictCache.put("ADDRESS_BLACKLIST", addressBlacklistLabels);
        dictCache.put("ADDRESS_BLACKLIST_PREFIX", addressBlacklistPrefix);
        dictCache.put("ADDRESS_SUFFIXES", addressSuffixes);

        log.info("字典加载完成: SURNAME_WHITELIST={}, PERSON_BLACKLIST={}, ADDRESS_BLACKLIST={}, ADDRESS_SUFFIXES={}",
                surnameWhitelist.size(), personBlacklist.size(), addressBlacklistLabels.size(), addressSuffixes.size());
    }

    /**
     * 合并 DB 词条与代码默认值。DB 中 is_enabled=false 的词条会从默认值中移除。
     * DB 中 is_enabled=true 的词条追加到默认值中。
     */
    private Set<String> mergeDict(Map<String, List<SensitiveDict>> grouped,
            String dictType, Set<String> defaults) {
        Set<String> result = new HashSet<>(defaults);
        List<SensitiveDict> dbItems = grouped.getOrDefault(dictType, Collections.emptyList());
        for (SensitiveDict item : dbItems) {
            if (Boolean.TRUE.equals(item.getIsEnabled())) {
                result.add(item.getTerm());
            } else {
                result.remove(item.getTerm());
            }
        }
        return result;
    }

    public Map<String, Set<String>> getDictCache() {
        return Collections.unmodifiableMap(dictCache);
    }

    // ======================== 内置规则覆盖 ========================

    /**
     * 从 DB 加载 BUILTIN 规则，覆盖 PatternRegistry 代码默认值。
     */
    public synchronized void reloadBuiltinRules() {
        List<SensitiveRule> allRules = ruleMapper.selectEnabled();
        int count = 0;
        for (SensitiveRule rule : allRules) {
            if (!"BUILTIN".equals(rule.getRuleType())) {
                continue;
            }
            try {
                SensitiveType type = SensitiveType.valueOf(rule.getPatternName());
                PatternRegistry.overridePattern(type, rule.getRegex());
                count++;
                log.info("内置规则覆盖: {} -> [{}]", rule.getPatternName(), rule.getRegex());
            } catch (IllegalArgumentException e) {
                log.error("内置规则加载失败: {} -> {}", rule.getPatternName(), e.getMessage());
            }
        }
        log.info("内置规则覆盖完成: {} 条", count);
    }

    // ======================== 词典 CRUD ========================

    public List<SensitiveDict> listDicts(String dictType) {
        return dictMapper.selectByType(dictType);
    }

    public void addDict(SensitiveDict dict) {
        if (dict.getIsEnabled() == null) {
            dict.setIsEnabled(true);
        }
        dictMapper.insert(dict);
        reloadDicts();
    }

    public void deleteDict(Long id) {
        dictMapper.deleteById(id);
        reloadDicts();
    }
}
