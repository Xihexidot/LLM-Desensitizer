package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.ScenarioPerception.ScenarioAnalysisResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责基于正则的模式检测与初步置信度评分（后续可抽取为独立的 ConfidenceScorer）。
 */
@Component
public class RegexPatternDetector {

    @Autowired
    private ConfidenceScorer confidenceScorer;

    public List<SensitiveEntity> detectWithPattern(String text, SensitiveType type, Pattern pattern,
            ScenarioAnalysisResult context) {
        List<SensitiveEntity> entities = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);

        boolean strictMode = context != null && context.isStrictModeForType(type);
        double threshold = context != null ? context.getThresholdForType(type, 0.5) : 0.5;

        while (matcher.find()) {
            String matchedText = matcher.group();
            double confidence = confidenceScorer.calculateConfidence(text, matcher.start(), matcher.end(), matchedText,
                    type,
                    strictMode);

            // 记录但不直接依赖日志框架（调用方会记录需要的日志）
            if (confidence >= threshold) {
                SensitiveEntity entity = SensitiveEntity.builder()
                        .type(type)
                        .originalText(matchedText)
                        .start(matcher.start())
                        .end(matcher.end())
                        .confidence(confidence)
                        .build();
                entities.add(entity);
            }
        }

        return entities;
    }

    public List<SensitiveEntity> detectCustomPattern(String text, String patternName, Pattern pattern) {
        List<SensitiveEntity> entities = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String matchedText = matcher.group();
            SensitiveEntity entity = SensitiveEntity.builder()
                    .type(SensitiveType.CUSTOM)
                    .originalText(matchedText)
                    .start(matcher.start())
                    .end(matcher.end())
                    .confidence(0.8)
                    .build();
            entities.add(entity);
        }

        return entities;
    }

    // confidence calculation moved to ConfidenceScorer
}
