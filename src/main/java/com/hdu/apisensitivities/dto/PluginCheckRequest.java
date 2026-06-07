package com.hdu.apisensitivities.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginCheckRequest {
    private String content;
    private String dataType;
    private String language;
    private String userId;
    private String department;
    private String targetProvider;
    private boolean strictMode;
    private boolean autoScenarioDetection;
}
