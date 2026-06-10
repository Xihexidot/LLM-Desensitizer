package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component
public class BinaryDataDetector {

    public List<SensitiveEntity> detectBinary(byte[] binaryData, String dataType, String language,
            Set<String> includeTypes, SensitiveDetectionService detectionService) {
        // 简化实现：未提取文本时返回空列表
        return Collections.emptyList();
    }
}
