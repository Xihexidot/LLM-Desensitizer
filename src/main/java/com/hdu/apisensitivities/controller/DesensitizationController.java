package com.hdu.apisensitivities.controller;

import com.hdu.apisensitivities.service.DataParser.DataParserManager;
import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.service.DesensitizationManager;
import com.hdu.apisensitivities.service.SensitiveDetection.SensitiveDetectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 脱敏控制器
 * 提供多种数据类型的敏感信息处理功能，包括文本、结构化数据、二进制数据和批量处理
 * 该控制器作为系统入口点，接收外部脱敏请求并将其转发给相应的处理服务
 */
@RestController
@RequestMapping("/desensitize")
public class DesensitizationController {

    private final DesensitizationManager desensitizationManager;
    private final DataParserManager dataParserManager;

    @Autowired
    public DesensitizationController(DesensitizationManager desensitizationManager,
                                     SensitiveDetectionService detectionService,
                                     DataParserManager dataParserManager) {
        this.desensitizationManager = desensitizationManager;
        this.dataParserManager = dataParserManager;
    }

    /**
     * 处理文本脱敏请求
     * 接收包含文本内容的脱敏请求，并返回脱敏后的结果
     *
     * @param request 包含待脱敏文本内容的请求对象
     * @return 包含脱敏结果的响应实体
     */
    @PostMapping("/text")
    public ResponseEntity<DesensitizationResponse> desensitizeText(
            @RequestBody DesensitizationRequest request) {
        // 确保数据类型设置正确
        if (request.getDataType() == null) {
            request.setDataType("TEXT");
        }
        DesensitizationResponse response = desensitizationManager.process(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 处理结构化数据脱敏请求
     * 支持JSON、XML等结构化数据格式的脱敏处理
     *
     * @param request 包含结构化数据内容的脱敏请求对象
     * @return 包含脱敏后结构化数据的响应实体
     */
    @PostMapping("/structured")
    public ResponseEntity<DesensitizationResponse> desensitizeStructuredData(
            @RequestBody DesensitizationRequest request) {
        // 验证请求是否包含结构化数据
        if (!request.isStructuredData()) {
            return ResponseEntity.badRequest().body(
                    new DesensitizationResponse(null, null, null, false, "无效的结构化数据请求"));
        }

        // 确保数据类型设置正确
        if (request.getDataType() == null) {
            request.setDataType("JSON"); // 默认使用JSON
        }

        DesensitizationResponse response = desensitizationManager.process(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 处理二进制数据脱敏请求
     * 支持图片、音频、PDF等媒体文件的脱敏处理
     *
     * @param file     上传的二进制文件
     * @param dataType 指定的数据类型（可选），如果未提供则自动推断
     * @param language 指定的语言类型（可选），默认为中文
     * @return 包含脱敏后二进制数据的响应实体
     */
    @PostMapping("/binary")
    public ResponseEntity<DesensitizationResponse> desensitizeBinaryData(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dataType", required = false) String dataType,
            @RequestParam(value = "language", required = false) String language) {
        try {
            // 检查文件是否为空
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        new DesensitizationResponse(null, null, null, false, "上传文件不能为空"));
            }

            // 构建脱敏请求
            DesensitizationRequest request = DesensitizationRequest.builder()
                    .binaryData(file.getBytes())
                    .dataType(dataType != null ? dataType : dataParserManager.inferDataType(file.getOriginalFilename()))
                    .language(language != null ? language : "zh")
                    .build();

            DesensitizationResponse response = desensitizationManager.process(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new DesensitizationResponse(null, null, null, false, "处理二进制数据失败: " + e.getMessage()));
        }
    }

    /**
     * 批量处理脱敏请求
     * 同时处理多个脱敏请求，提高处理效率
     *
     * @param requests 包含多个脱敏请求的列表
     * @return 包含所有脱敏结果的映射，键为原始请求内容，值为对应的脱敏响应
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, DesensitizationResponse>> batchDesensitize(
            @RequestBody List<DesensitizationRequest> requests) {
        Map<String, DesensitizationResponse> responses = requests.parallelStream()
                .collect(java.util.stream.Collectors.toMap(
                        DesensitizationRequest::getContent,
                        desensitizationManager::process
                ));
        return ResponseEntity.ok(responses);
    }


    /**
     * 健康检查端点
     * 用于验证服务是否正常运行
     *
     * @return 表示服务健康状态的简单字符串
     */
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Service is healthy");
    }
}