package com.hdu.apisensitivities.service.SensitiveDetection;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 管理用户自定义正则模式的组件。
 */
@Component
public class CustomPatternManager {

    private final Map<String, Pattern> customPatterns = new HashMap<>();

    public void addCustomPattern(String patternName, String regex) {
        Pattern pattern = Pattern.compile(regex);
        customPatterns.put(patternName, pattern);
    }

    public void removeCustomPattern(String patternName) {
        customPatterns.remove(patternName);
    }

    public Map<String, Pattern> getAllPatterns() {
        return Collections.unmodifiableMap(new HashMap<>(customPatterns));
    }
}
