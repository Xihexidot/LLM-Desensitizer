package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.entity.SensitiveEntity;

import java.util.List;
import java.util.Set;

public interface BinarySensitiveDetectionService {
    List<SensitiveEntity> detectSensitiveInfoInBinary(byte[] binaryData, String dataType, String language);

    List<SensitiveEntity> detectSensitiveInfoInBinary(byte[] binaryData, String dataType, String language,
            Set<String> includeTypes);
}
