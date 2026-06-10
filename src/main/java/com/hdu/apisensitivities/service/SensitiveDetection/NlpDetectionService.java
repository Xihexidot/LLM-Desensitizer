package com.hdu.apisensitivities.service.SensitiveDetection;

import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.utils.NlpEntityDetector;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * NLP 实体检测的薄封装，便于替换或 mock。
 */
@Component
public class NlpDetectionService {

    public List<SensitiveEntity> detect(String text) {
        return NlpEntityDetector.detect(text);
    }
}
