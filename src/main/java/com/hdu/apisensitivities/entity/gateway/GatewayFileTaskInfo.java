package com.hdu.apisensitivities.entity.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayFileTaskInfo {
    private String taskId;
    private GatewayTaskStatus status;
    private Integer progress;
    private String fileName;
    private String sceneCode;
    private GatewayDecisionAction decisionAction;
    private String resultUrl;
    private String errorMessage;
}
