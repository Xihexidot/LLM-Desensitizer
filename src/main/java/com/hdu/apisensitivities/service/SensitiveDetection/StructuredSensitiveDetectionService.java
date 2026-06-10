package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.entity.SensitiveEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface StructuredSensitiveDetectionService {
    List<SensitiveEntity> detectSensitiveInfoInStructuredData(Map<String, Object> structuredData, String language);

    List<SensitiveEntity> detectSensitiveInfoInStructuredData(Map<String, Object> structuredData, String language,
            Set<String> includeTypes);
}
