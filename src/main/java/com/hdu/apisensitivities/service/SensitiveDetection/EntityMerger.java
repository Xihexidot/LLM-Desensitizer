package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 实体合并工具，负责合并重叠或相邻的敏感实体。
 */
@Component
public class EntityMerger {

    public List<SensitiveEntity> mergeEntities(List<SensitiveEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return entities;
        }

        // 按起始和结束位置排序
        entities.sort(Comparator.comparingInt(SensitiveEntity::getStart)
                .thenComparingInt(SensitiveEntity::getEnd));

        List<SensitiveEntity> merged = new ArrayList<>();
        SensitiveEntity current = entities.get(0);

        for (int i = 1; i < entities.size(); i++) {
            SensitiveEntity next = entities.get(i);

            String currentPath = current.getMetadata() == null ? null : (String) current.getMetadata().get("fieldPath");
            String nextPath = next.getMetadata() == null ? null : (String) next.getMetadata().get("fieldPath");
            boolean hasDifferentFieldPaths = currentPath != null && nextPath != null && !currentPath.equals(nextPath);

            if (hasDifferentFieldPaths || current.getEnd() < next.getStart()) {
                merged.add(current);
                current = next;
                continue;
            }

            if (current.getType() == next.getType()) {
                int start = Math.min(current.getStart(), next.getStart());
                int end = Math.max(current.getEnd(), next.getEnd());
                current.setStart(start);
                current.setEnd(end);

                String currentText = current.getOriginalText() != null ? current.getOriginalText() : "";
                String nextText = next.getOriginalText() != null ? next.getOriginalText() : "";
                current.setOriginalText(currentText.length() >= nextText.length() ? currentText : nextText);
                current.setConfidence(Math.max(current.getConfidence(), next.getConfidence()));

                if (next.getMetadata() != null && !next.getMetadata().isEmpty()) {
                    current.getMetadata().putAll(next.getMetadata());
                }
            } else {
                merged.add(current);
                current = next;
            }
        }

        merged.add(current);
        return merged;
    }
}
