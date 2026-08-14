package com.hdu.apisensitivities.dto;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.gateway.GatewayDecisionAction;
import com.hdu.apisensitivities.entity.gateway.GatewayRiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginCheckResponse {
    private List<SensitiveEntity> detectedEntities;
    private String desensitizedContent;
    private String auditEventId;
    private GatewayRiskLevel riskLevel;
    private GatewayDecisionAction decisionAction;

    /**
     * 脱敏标记与原信息的比对映射（占位符 → 原始明文），供插件"一键复原"解码还原使用。
     * <p>
     * 来源于会话级缓存 {@code GlobalSessionContextRepository} 的反向导出，
     * 同一会话内同一明文始终映射到同一占位符，保证脱敏与复原过程的数据一致性。
     * </p>
     */
    private Map<String, String> maskMapping;
}
