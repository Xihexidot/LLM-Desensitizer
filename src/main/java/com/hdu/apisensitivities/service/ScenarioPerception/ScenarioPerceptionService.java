package com.hdu.apisensitivities.service.ScenarioPerception;

import com.hdu.apisensitivities.entity.DesensitizationRequest;

/**
 * 情景感知服务接口
 * 用于分析用户请求的情景，并根据情景调整敏感信息识别的范围
 */
public interface ScenarioPerceptionService {

    /**
     * 分析请求的情景
     * @param request 脱敏请求
     * @return 情景分析结果，包含情景类型和相关参数
     */
    ScenarioAnalysisResult analyzeScenario(DesensitizationRequest request);

    /**
     * 根据情景分析结果调整敏感信息识别的范围
     * @param request 脱敏请求
     * @param analysisResult 情景分析结果
     */
    void adjustDetectionScope(DesensitizationRequest request, ScenarioAnalysisResult analysisResult);

    /**
     * 获取默认的情景分析结果
     * @return 默认的情景分析结果
     */
    ScenarioAnalysisResult getDefaultScenario();
}