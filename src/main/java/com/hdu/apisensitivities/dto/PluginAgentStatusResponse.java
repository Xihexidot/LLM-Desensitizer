package com.hdu.apisensitivities.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginAgentStatusResponse {
    private boolean enabled;
    private boolean reachable;
    private String mode;
    private String endpoint;
    private String model;
    private String message;
}
