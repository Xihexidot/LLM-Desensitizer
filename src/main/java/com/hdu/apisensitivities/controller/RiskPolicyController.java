package com.hdu.apisensitivities.controller;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.bind.annotation.*;

/**
 * 风险策略配置 API。
 * 前端 RiskPolicy.vue 的编辑结果通过此接口持久化，
 * 网关 buildRiskDecision 读取此配置进行在线风险判定。
 */
@RestController
@RequestMapping("/gateway/v1")
public class RiskPolicyController {

    /**
     * 场景策略定义
     */
    public static class ScenePolicy {
        public int id;
        public String sceneName;
        public List<String> types = new ArrayList<>(); // 风险决策关注的敏感类型
        public List<String> detectTypes = new ArrayList<>(); // 该场景需要检测的敏感类型(空=全部)
        public int threshold; // 0 = 任意命中
        public String riskLevel; // LOW/MEDIUM/HIGH
        public String action; // ALLOW/DESENSITIZE_AND_ALLOW/BLOCK/ROUTE_TO_INTERNAL_MODEL
        public boolean enabled = true;
    }

    /**
     * 全局策略配置
     */
    public static class GlobalPolicy {
        public String defaultAction = "DESENSITIZE_AND_ALLOW";
        public int maxSensitiveCount = 5;
        public boolean requireOutputReview = false;
    }

    /**
     * 完整策略配置
     */
    public static class PolicyConfig {
        public GlobalPolicy global = new GlobalPolicy();
        public List<ScenePolicy> scenes = new ArrayList<>();
    }

    /** 内存存储，重启恢复默认 */
    private static final PolicyConfig CONFIG = new PolicyConfig();

    static {
        CONFIG.global = new GlobalPolicy();
        CONFIG.scenes.add(createScene(1, "客服场景",
                List.of("PHONE_NUMBER", "ADDRESS", "ID_CARD"),
                List.of("PHONE_NUMBER", "ID_CARD", "EMAIL", "ADDRESS", "PERSON_NAME"),
                1, "MEDIUM", "DESENSITIZE_AND_ALLOW"));
        CONFIG.scenes.add(createScene(2, "金融场景",
                List.of("BANK_CARD", "ID_CARD", "PASSWORD"),
                List.of("BANK_CARD", "ID_CARD", "PASSWORD", "CREDIT_CARD", "SOCIAL_SECURITY"),
                0, "HIGH", "BLOCK"));
        CONFIG.scenes.add(createScene(3, "医疗场景",
                List.of("ID_CARD", "SOCIAL_SECURITY", "BIRTH_DATE"),
                List.of("ID_CARD", "SOCIAL_SECURITY", "BIRTH_DATE", "PERSON_NAME"),
                0, "HIGH", "BLOCK"));
        CONFIG.scenes.add(createScene(4, "研发场景",
                List.of("API_KEY", "PASSWORD"),
                List.of("API_KEY", "PASSWORD"),
                1, "HIGH", "BLOCK"));
        CONFIG.scenes.add(createScene(5, "招聘场景",
                List.of("PHONE_NUMBER", "ID_CARD"),
                List.of("PHONE_NUMBER", "ID_CARD", "EMAIL", "PERSON_NAME"),
                2, "MEDIUM", "DESENSITIZE_AND_ALLOW"));
        CONFIG.scenes.add(createScene(6, "通用场景",
                List.of("PHONE_NUMBER", "EMAIL", "ADDRESS"),
                List.of("PHONE_NUMBER", "ID_CARD", "BANK_CARD", "EMAIL", "ADDRESS", "PASSWORD", "API_KEY"),
                3, "MEDIUM", "DESENSITIZE_AND_ALLOW"));
    }

    private static ScenePolicy createScene(int id, String name, List<String> types, List<String> detectTypes,
            int threshold, String riskLevel, String action) {
        ScenePolicy p = new ScenePolicy();
        p.id = id;
        p.sceneName = name;
        p.types.addAll(types);
        if (detectTypes != null) {
            p.detectTypes.addAll(detectTypes);
        }
        p.threshold = threshold;
        p.riskLevel = riskLevel;
        p.action = action;
        p.enabled = true;
        return p;
    }

    @GetMapping("/risk-policy")
    public PolicyConfig getConfig() {
        return CONFIG;
    }

    @PutMapping("/risk-policy")
    public PolicyConfig updateConfig(@RequestBody PolicyConfig newConfig) {
        if (newConfig.global != null) {
            CONFIG.global = newConfig.global;
        }
        if (newConfig.scenes != null) {
            CONFIG.scenes = newConfig.scenes;
        }
        return CONFIG;
    }

    /** 供网关服务读取 */
    public static PolicyConfig getCurrentConfig() {
        return CONFIG;
    }
}
