package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 处理结构化数据（Map/List/String）递归检测的工具，接受外部检测器回调以避免循环注入。
 */
@Component
public class StructuredDataDetector {

    public void detectInStructuredData(Object data, String fieldPath, List<SensitiveEntity> entities, String language,
            Set<String> includeTypes, TextSensitiveDetectionService detector) {
        if (data == null) {
            return;
        }

        if (data instanceof String) {
            String text = (String) data;
            List<SensitiveEntity> fieldEntities = detector.detectSensitiveInfo(text, language, includeTypes);
            for (SensitiveEntity entity : fieldEntities) {
                if (fieldPath != null && !fieldPath.isEmpty()) {
                    entity.addFieldPath(fieldPath);
                }
                entities.add(entity);
            }
        } else if (data instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) data;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() != null ? entry.getKey().toString() : "";
                String newFieldPath = (fieldPath == null || fieldPath.isEmpty()) ? key : fieldPath + "." + key;
                detectInStructuredData(entry.getValue(), newFieldPath, entities, language, includeTypes, detector);
            }
        } else if (data instanceof List) {
            List<?> list = (List<?>) data;
            for (int i = 0; i < list.size(); i++) {
                String newFieldPath = (fieldPath == null || fieldPath.isEmpty()) ? "[" + i + "]"
                        : fieldPath + "[" + i + "]";
                detectInStructuredData(list.get(i), newFieldPath, entities, language, includeTypes, detector);
            }
        }
        // 其他类型暂不处理
    }
}
