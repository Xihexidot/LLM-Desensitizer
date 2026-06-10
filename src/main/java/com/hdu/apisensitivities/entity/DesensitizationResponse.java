package com.hdu.apisensitivities.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 脱敏处理响应实体，封装脱敏操作的结果。
 * <p>
 * 包含脱敏前后的内容、检测到的敏感实体列表、处理状态及错误信息。
 * 通常由 {@link com.hdu.apisensitivities.service.DesensitizationManager} 返回。
 * </p>
 *
 * @author yourname
 * @since 1.0.0
 * @see DesensitizationRequest
 * @see com.hdu.apisensitivities.service.DesensitizationManager
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DesensitizationResponse {

    /**
     * 原始内容（脱敏前）。
     * <p>
     * 与请求中的原始数据一致，未经任何修改。若处理失败，该字段仍会返回原始输入。
     * </p>
     */
    private String originalContent;

    /**
     * 脱敏后的内容。
     * <p>
     * 已应用脱敏策略（如替换、遮盖、加密）的文本。若未检测到敏感信息或处理失败，
     * 该字段值与 {@link #originalContent} 相同。
     * </p>
     */
    private String desensitizedContent;

    /**
     * 检测到的敏感实体列表。
     * <p>
     * 包含所有识别出的敏感信息对象，每个对象记录了类型、位置、原始值等信息。
     * 若无敏感信息或处理失败，返回空列表（非 null）。
     * </p>
     */
    private List<SensitiveEntity> detectedEntities;

    /**
     * 处理是否成功。
     * <p>
     * {@code true} 表示脱敏流程正常完成（即使未检测到敏感信息也算成功）；
     * {@code false} 表示发生了异常（如数据解析失败、策略执行错误等）。
     * </p>
     */
    private boolean success;

    /**
     * 附加消息，通常用于描述处理结果或错误原因。
     * <p>
     * 成功时可为 {@code "脱敏处理成功"} 或自定义信息；失败时包含异常详情。
     * </p>
     */
    private String message;
}