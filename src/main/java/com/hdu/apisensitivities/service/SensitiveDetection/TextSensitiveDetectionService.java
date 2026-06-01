package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.service.ScenarioPerception.ScenarioAnalysisResult;

import java.util.List;
import java.util.Set;

public interface TextSensitiveDetectionService {
    List<SensitiveEntity> detectSensitiveInfo(String text, String language);

    List<SensitiveEntity> detectSensitiveInfo(String text, String language, Set<String> includeTypes);

    List<SensitiveEntity> detectSensitiveInfo(String text, String language, Set<String> includeTypes,
            ScenarioAnalysisResult context);
}
