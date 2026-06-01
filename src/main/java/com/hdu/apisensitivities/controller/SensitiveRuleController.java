package com.hdu.apisensitivities.controller;

import com.hdu.apisensitivities.dto.SensitiveRule;
import com.hdu.apisensitivities.service.SensitiveDetection.SensitiveRuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 敏感规则控制器
 * 提供对敏感数据检测规则的增删改查及状态管理功能
 * 通过该控制器可以动态管理敏感信息检测规则，如个人信息、证件号码等的匹配模式
 */
@RestController
@RequestMapping("/rules")
public class SensitiveRuleController {

    private final SensitiveRuleService sensitiveRuleService;

    /**
     * 构造函数，初始化敏感规则控制器所需的依赖项
     *
     * @param sensitiveRuleService 敏感规则服务，负责执行具体的规则管理操作
     */
    public SensitiveRuleController(SensitiveRuleService sensitiveRuleService) {
        this.sensitiveRuleService = sensitiveRuleService;
    }

    /**
     * 获取所有敏感规则
     * 返回系统中当前定义的所有敏感数据检测规则
     *
     * @return 包含所有敏感规则的列表
     */
    @GetMapping
    public ResponseEntity<List<SensitiveRule>> getAllRules() {
        return ResponseEntity.ok(sensitiveRuleService.getAllRules());
    }

    /**
     * 添加新的敏感规则
     * 接收一个新的敏感规则定义并保存到系统中
     *
     * @param rule 待添加的敏感规则对象
     * @return 操作结果消息
     */
    @PostMapping
    public ResponseEntity<String> addRule(@RequestBody SensitiveRule rule) {
        try {
            sensitiveRuleService.addRule(rule);
            return ResponseEntity.ok("规则添加成功");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 更新现有敏感规则
     * 根据路径参数中的模式名称更新对应的规则定义
     *
     * @param patternName 规则的唯一标识符（模式名称）
     * @param rule 更新后的规则对象
     * @return 操作结果消息
     */
    @PutMapping("/{patternName}")
    public ResponseEntity<String> updateRule(@PathVariable String patternName, @RequestBody SensitiveRule rule) {
        try {
            rule.setPatternName(patternName);
            sensitiveRuleService.updateRule(rule);
            return ResponseEntity.ok("规则更新成功");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 删除敏感规则
     * 根据路径参数中的模式名称删除对应的规则
     *
     * @param patternName 要删除的规则的唯一标识符（模式名称）
     * @return 操作结果消息
     */
    @DeleteMapping("/{patternName}")
    public ResponseEntity<String> deleteRule(@PathVariable String patternName) {
        try {
            sensitiveRuleService.deleteRule(patternName);
            return ResponseEntity.ok("规则删除成功");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 切换规则启用状态
     * 启用或禁用指定的敏感规则，而不删除它
     *
     * @param patternName 规则的唯一标识符（模式名称）
     * @param enabled 新的状态值，true表示启用，false表示禁用
     * @return 操作结果消息
     */
    @PatchMapping("/{patternName}/status")
    public ResponseEntity<String> toggleRuleStatus(@PathVariable String patternName, @RequestParam boolean enabled) {
        try {
            sensitiveRuleService.toggleRule(patternName, enabled);
            return ResponseEntity.ok(enabled ? "规则已启用" : "规则已禁用");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
