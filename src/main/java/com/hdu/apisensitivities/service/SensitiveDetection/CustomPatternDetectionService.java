package com.hdu.apisensitivities.service.SensitiveDetection;

import java.util.Map;
import java.util.regex.Pattern;

public interface CustomPatternDetectionService {
    void addCustomPattern(String patternName, String regex);

    void removeCustomPattern(String patternName);

    Map<String, Pattern> getCustomPatterns();
}
