package com.hdu.apisensitivities;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.SensitiveType;
import com.hdu.apisensitivities.service.DesensitizationManager;
import com.hdu.apisensitivities.service.SensitiveDetection.SensitiveDetectionService;
import com.hdu.apisensitivities.entity.DesensitizationResponse;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ApiSensitivitiesApplicationTests {

    @Test
    void contextLoads() {
    }

    @Autowired
    private SensitiveDetectionService detectionService;

    @Autowired
    private DesensitizationManager desensitizationManager;

    @Test
    void detectIpv4Address() {
        String text = "登录失败，来源IP为 192.168.10.25，请检查";
        var entities = detectionService.detectSensitiveInfo(text, "zh");
        assertTrue(entities.stream()
                .anyMatch(e -> e.getType() == SensitiveType.IP_ADDRESS && "192.168.10.25".equals(e.getOriginalText())));
    }

    @Test
    void detectIpv6Address() {
        String text = "访问来自 fe80:0:0:0:200:f8ff:fe21:67cf";
        var entities = detectionService.detectSensitiveInfo(text, "zh");
        assertTrue(entities.stream().anyMatch(e -> e.getType() == SensitiveType.IP_ADDRESS));
    }

    @Test
    void maskIpAddress() {
        String text = "登录失败，来源IP为 192.168.10.25，请检查";
        DesensitizationRequest req = DesensitizationRequest.builder()
                .content(text)
                .language("zh")
                .dataType("TEXT")
                .strategy("maskDesensitizationStrategy")
                .build();
        DesensitizationResponse resp = desensitizationManager.process(req);
        assertNotNull(resp.getDesensitizedContent());
        assertTrue(resp.getDesensitizedContent().matches(".*\\[IP(_\\d+)?\\].*"));
    }

    @Test
    void partialMaskIpAddress() {
        String text = "来源IP: 10.0.0.123";
        DesensitizationRequest req = DesensitizationRequest.builder()
                .content(text)
                .language("zh")
                .dataType("TEXT")
                .strategy("partialDesensitizationStrategy")
                .build();
        DesensitizationResponse resp = desensitizationManager.process(req);
        assertNotNull(resp.getDesensitizedContent());
        assertTrue(resp.getDesensitizedContent().matches(".*10\\.0\\.\\*\\.\\*.*"));
    }

}
