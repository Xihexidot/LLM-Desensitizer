package com.hdu.apisensitivities.controller;

import com.hdu.apisensitivities.service.monitor.MonitorAnomalyService;
import com.hdu.apisensitivities.service.monitor.MonitorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业员工外部 LLM 调用监控接口（仅限安全审计 / 运维管理角色，见 MonitorAuthInterceptor）。
 * <p>
 * 提供当日调用总量、分平台统计、渠道拆分、按小时趋势与异常风险告警，供监控页面实时展示。
 * 所有数据均为调用次数聚合统计，员工敏感内容已在审计环节脱敏/加密，符合数据隐私合规要求。
 * </p>
 */
@RestController
@RequestMapping("/gateway/v1/monitor")
public class MonitorController {

    private final MonitorService monitorService;
    private final MonitorAnomalyService anomalyService;

    public MonitorController(MonitorService monitorService, MonitorAnomalyService anomalyService) {
        this.monitorService = monitorService;
        this.anomalyService = anomalyService;
    }

    /** 当日监控概览：总次数、渠道拆分、分平台统计、风险/决策分布、异常告警数 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return monitorService.getOverview(LocalDate.now().toString());
    }

    /** 按小时调用趋势（默认近 24 小时，支持 1-48） */
    @GetMapping("/trend")
    public Map<String, Object> trend(@RequestParam(defaultValue = "24") int hours) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", LocalDate.now().toString());
        result.put("hours", hours);
        result.put("points", monitorService.getTrend(LocalDate.now().toString(), hours));
        return result;
    }

    /** 异常风险检测与告警列表 */
    @GetMapping("/anomalies")
    public Map<String, Object> anomalies() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", LocalDate.now().toString());
        List<Map<String, Object>> list = anomalyService.detect(LocalDate.now().toString());
        result.put("count", list.size());
        result.put("items", list);
        return result;
    }
}
