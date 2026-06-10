package com.hdu.apisensitivities.controller;

import com.hdu.apisensitivities.dto.SensitiveDict;
import com.hdu.apisensitivities.service.SensitiveDetection.DictConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 敏感词典控制器。
 * 提供白名单/黑名单词条的 CRUD 操作，企业管理后台可通过该接口动态调整检测策略。
 */
@RestController
@RequestMapping("/dict")
public class SensitiveDictController {

    private final DictConfigService dictConfigService;

    public SensitiveDictController(DictConfigService dictConfigService) {
        this.dictConfigService = dictConfigService;
    }

    /**
     * 获取当前词典缓存快照（只读）。
     */
    @GetMapping("/cache")
    public ResponseEntity<Map<String, java.util.Set<String>>> getCache() {
        return ResponseEntity.ok(dictConfigService.getDictCache());
    }

    /**
     * 按类型列出所有词条。
     */
    @GetMapping
    public ResponseEntity<List<SensitiveDict>> listDicts(@RequestParam String dictType) {
        return ResponseEntity.ok(dictConfigService.listDicts(dictType));
    }

    /**
     * 添加词条（立即生效）。
     */
    @PostMapping
    public ResponseEntity<String> addDict(@RequestBody SensitiveDict dict) {
        dictConfigService.addDict(dict);
        return ResponseEntity.ok("词条添加成功");
    }

    /**
     * 删除词条（立即生效）。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteDict(@PathVariable Long id) {
        dictConfigService.deleteDict(id);
        return ResponseEntity.ok("词条已删除");
    }

    /**
     * 热重载所有词典和内置规则。
     */
    @PostMapping("/reload")
    public ResponseEntity<String> reload() {
        dictConfigService.reloadDicts();
        dictConfigService.reloadBuiltinRules();
        return ResponseEntity.ok("词典重载完成");
    }
}
