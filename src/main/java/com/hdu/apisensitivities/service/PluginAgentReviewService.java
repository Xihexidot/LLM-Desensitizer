package com.hdu.apisensitivities.service;

import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.SensitiveDetection.NlpScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class PluginAgentReviewService {

    @Value("${plugin.agent.enhanced-review-enabled:true}")
    private boolean enhancedReviewEnabled;

    @Value("${plugin.agent.fail-open:true}")
    private boolean failOpen;

    private final NlpScanner nlpScanner;

    public PluginAgentReviewService(NlpScanner nlpScanner) {
        this.nlpScanner = nlpScanner;
    }

    public record AgentReviewResult(boolean enabled, boolean available, boolean used, boolean dangerous, String mode,
            String endpoint, String model, String message, String desensitizedContent, Map<String, String> maskMapping,
            List<SensitiveEntity> agentEntities, List<String> semanticEntities) {
    }

    public AgentReviewResult review(String originalContent, DesensitizationResponse baseResult) {
        if (!enhancedReviewEnabled) {
            return emptyResult(false, false, false, "RULE_NER", "插件 Agent 增强未启用");
        }

        NlpScanner.AgentStatus status = nlpScanner.getStatus();
        if (!status.reachable()) {
            String message = failOpen ? "Agent 不可用，已自动降级到正则 + NER" : "Agent 不可用";
            return new AgentReviewResult(
                    true,
                    false,
                    false,
                    false,
                    "RULE_NER",
                    status.endpoint(),
                    status.model(),
                    message + "；原因: " + status.message(),
                    safeDesensitizedContent(baseResult),
                    Collections.emptyMap(),
                    Collections.emptyList(),
                    Collections.emptyList());
        }

        List<String> semanticEntities = filterSemanticEntities(
                originalContent != null ? originalContent : safeDesensitizedContent(baseResult),
                safeDesensitizedContent(baseResult),
                baseResult != null ? baseResult.getMaskMapping() : Collections.emptyMap(),
                nlpScanner.extractEntities(originalContent));

        if (semanticEntities.isEmpty()) {
            return new AgentReviewResult(
                    true,
                    true,
                    false,
                    false,
                    "RULE_NER_OLLAMA",
                    status.endpoint(),
                    status.model(),
                    "Agent 已接入，但未识别到新增语义敏感实体",
                    safeDesensitizedContent(baseResult),
                    Collections.emptyMap(),
                    Collections.emptyList(),
                    Collections.emptyList());
        }

        MaskApplyResult maskApplyResult = applySemanticMask(safeDesensitizedContent(baseResult), semanticEntities);
        boolean dangerous = nlpScanner.checkSafety(maskApplyResult.content());

        return new AgentReviewResult(
                true,
                true,
                true,
                dangerous,
                "RULE_NER_OLLAMA",
                status.endpoint(),
                status.model(),
                dangerous ? "Agent 复检提示仍存在残留风险，已提升风险等级"
                        : "Agent 增强识别成功，已补充语义脱敏实体",
                maskApplyResult.content(),
                maskApplyResult.maskMapping(),
                buildAgentEntities(semanticEntities, originalContent),
                semanticEntities);
    }

    private AgentReviewResult emptyResult(boolean enabled, boolean available, boolean used, String mode,
            String message) {
        return new AgentReviewResult(enabled, available, used, false, mode, null, null, message, null,
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
    }

    private String safeDesensitizedContent(DesensitizationResponse baseResult) {
        if (baseResult == null || baseResult.getDesensitizedContent() == null) {
            return "";
        }
        return baseResult.getDesensitizedContent();
    }

    private List<String> filterSemanticEntities(String originalContent, String currentContent,
            Map<String, String> baseMaskMapping, List<String> rawEntities) {
        if (rawEntities == null || rawEntities.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> existingMaskedValues = new LinkedHashSet<>();
        if (baseMaskMapping != null) {
            existingMaskedValues.addAll(baseMaskMapping.values());
        }

        Set<String> filtered = new LinkedHashSet<>();
        for (String entity : rawEntities) {
            if (entity == null) {
                continue;
            }
            String normalized = entity.trim();
            if (normalized.length() < 2) {
                continue;
            }
            if (existingMaskedValues.contains(normalized)) {
                continue;
            }
            if (!containsIgnoreCase(originalContent, normalized) || !containsIgnoreCase(currentContent, normalized)) {
                continue;
            }
            filtered.add(normalized);
        }
        return new ArrayList<>(filtered);
    }

    private boolean containsIgnoreCase(String source, String target) {
        if (source == null || target == null) {
            return false;
        }
        return source.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT));
    }

    private MaskApplyResult applySemanticMask(String content, List<String> semanticEntities) {
        if (content == null || content.isEmpty() || semanticEntities == null || semanticEntities.isEmpty()) {
            return new MaskApplyResult(content, Collections.emptyMap());
        }

        List<String> sortedEntities = new ArrayList<>(semanticEntities);
        sortedEntities.sort((left, right) -> Integer.compare(right.length(), left.length()));

        String maskedContent = content;
        Map<String, String> maskMapping = new LinkedHashMap<>();
        int index = 1;
        for (String entity : sortedEntities) {
            if (entity == null || entity.isBlank() || !maskedContent.contains(entity)) {
                continue;
            }
            String placeholder = "[ENTITY_" + index + "]";
            maskedContent = maskedContent.replace(entity, placeholder);
            maskMapping.put(placeholder, entity);
            index++;
        }
        return new MaskApplyResult(maskedContent, maskMapping);
    }

    private List<SensitiveEntity> buildAgentEntities(List<String> semanticEntities, String originalContent) {
        if (semanticEntities == null || semanticEntities.isEmpty()) {
            return Collections.emptyList();
        }

        List<SensitiveEntity> entities = new ArrayList<>();
        for (String semanticEntity : semanticEntities) {
            int start = originalContent != null ? originalContent.indexOf(semanticEntity) : -1;
            int end = start >= 0 ? start + semanticEntity.length() : -1;
            SensitiveEntity entity = SensitiveEntity.builder()
                    .type(SensitiveType.CUSTOM)
                    .content(semanticEntity)
                    .originalText(semanticEntity)
                    .start(start)
                    .end(end)
                    .confidence(0.92)
                    .build();
            entity.addMetadata("source", "local-agent");
            entity.addMetadata("category", "semantic");
            entities.add(entity);
        }
        return entities;
    }

    private record MaskApplyResult(String content, Map<String, String> maskMapping) {
    }
}
