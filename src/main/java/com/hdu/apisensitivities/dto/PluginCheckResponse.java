package com.hdu.apisensitivities.dto;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginCheckResponse {
    private List<SensitiveEntity> detectedEntities;
    private String desensitizedContent;
    private String auditEventId;
}
