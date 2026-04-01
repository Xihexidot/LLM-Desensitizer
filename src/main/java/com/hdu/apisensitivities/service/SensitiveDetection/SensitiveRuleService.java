package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.dto.SensitiveRule;

import com.hdu.apisensitivities.mapper.SensitiveRuleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
@Service
public class SensitiveRuleService {

    private final SensitiveRuleMapper sensitiveRuleMapper;
    private final SensitiveDetectionService sensitiveDetectionService;

    @Autowired
    public SensitiveRuleService(SensitiveRuleMapper sensitiveRuleMapper,
                                SensitiveDetectionService sensitiveDetectionService) {
        this.sensitiveRuleMapper = sensitiveRuleMapper;
        this.sensitiveDetectionService = sensitiveDetectionService;
    }

    /**
     * 初始化时加载所有启用的规则
     */
    @PostConstruct
    public void init() {
        log.info("开始加载自定义敏感检测规则...");
        List<SensitiveRule> rules = sensitiveRuleMapper.selectEnabled();
        int count = 0;
        for (SensitiveRule rule : rules) {
            try {
                sensitiveDetectionService.addCustomPattern(rule.getPatternName(), rule.getRegex());
                count++;
            } catch (Exception e) {
                log.error("加载规则 {} 失败: {}", rule.getPatternName(), e.getMessage());
            }
        }
        log.info("成功加载 {} 个自定义规则", count);
    }

    public List<SensitiveRule> getAllRules() {
        return sensitiveRuleMapper.selectAll();
    }

    @Transactional
    public void addRule(SensitiveRule rule) {
        // 验证正则是否合法
        try {
            Pattern.compile(rule.getRegex());
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("无效的正则表达式: " + e.getDescription());
        }

        // 默认启用
        if (rule.getIsEnabled() == null) {
            rule.setIsEnabled(true);
        }

        if (sensitiveRuleMapper.selectByPatternName(rule.getPatternName()) != null) {
            throw new IllegalArgumentException("规则名称已存在: " + rule.getPatternName());
        }

        sensitiveRuleMapper.insert(rule);

        if (rule.getIsEnabled()) {
            sensitiveDetectionService.addCustomPattern(rule.getPatternName(), rule.getRegex());
        }
    }

    @Transactional
    public void updateRule(SensitiveRule rule) {
        SensitiveRule existing = sensitiveRuleMapper.selectByPatternName(rule.getPatternName());
        if (existing == null) {
            throw new IllegalArgumentException("规则不存在: " + rule.getPatternName());
        }

        // 验证正则
        if (rule.getRegex() != null) {
            try {
                Pattern.compile(rule.getRegex());
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("无效的正则表达式: " + e.getDescription());
            }
        }

        sensitiveRuleMapper.update(rule);

        // 刷新内存中的规则
        SensitiveRule updated = sensitiveRuleMapper.selectByPatternName(rule.getPatternName());
        if (updated.getIsEnabled()) {
            sensitiveDetectionService.addCustomPattern(updated.getPatternName(), updated.getRegex());
        } else {
            sensitiveDetectionService.removeCustomPattern(updated.getPatternName());
        }
    }

    @Transactional
    public void deleteRule(String patternName) {
        sensitiveRuleMapper.deleteByPatternName(patternName);
        sensitiveDetectionService.removeCustomPattern(patternName);
    }

    @Transactional
    public void toggleRule(String patternName, boolean enabled) {
        SensitiveRule rule = sensitiveRuleMapper.selectByPatternName(patternName);
        if (rule == null) {
            throw new IllegalArgumentException("规则不存在");
        }

        sensitiveRuleMapper.updateStatus(patternName, enabled);

        if (enabled) {
            sensitiveDetectionService.addCustomPattern(rule.getPatternName(), rule.getRegex());
        } else {
            sensitiveDetectionService.removeCustomPattern(patternName);
        }
    }
}

