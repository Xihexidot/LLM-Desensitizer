package com.hdu.apisensitivities.service.Desensitization;

import com.hdu.apisensitivities.config.DesensitizationRuleProperties;
import com.hdu.apisensitivities.entity.SensitiveEntity;
import com.hdu.apisensitivities.service.SensitiveDetection.TextSensitiveDetectionService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 脱敏结果双重校验器：算法校验 + 人工复核队列。
 * <p>
 * 算法校验（两轮）：
 * <ol>
 * <li><b>明文残留扫描</b>：逐个比对已识别实体，确认其明文不再出现在脱敏结果中；</li>
 * <li><b>整体二次扫描</b>：对脱敏结果重新执行敏感信息检测，捕获首轮漏识别但在脱敏后仍残留的敏感模式。</li>
 * </ol>
 * 当覆盖率达到 {@code coverage-threshold} 且无残留时判为通过；否则（且开启人工复核开关时）
 * 生成 {@link ManualReviewTask} 进入人工复核队列，供管理员人工裁决。
 * </p>
 */
@Slf4j
@Component
public class DesensitizationVerifier {

    private final DesensitizationRuleProperties ruleProperties;
    private final TextSensitiveDetectionService detectionService;

    private final Queue<ManualReviewTask> reviewQueue = new ConcurrentLinkedQueue<>();
    private static final int MAX_QUEUE_SIZE = 200;
    private static final AtomicLong REVIEW_SEQ = new AtomicLong(1);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String PLACEHOLDER_PATTERN = "^\\[[A-Z_]+(_\\d+)?\\]$";
    /** 二次扫描仅覆盖高置信正则类型，避免 NLP 将占位符/上下文词误判为残留 */
    private static final Set<String> RESCAN_TYPES = Set.of(
            "PHONE_NUMBER", "ID_CARD", "BANK_CARD", "CREDIT_CARD", "EMAIL",
            "IP_ADDRESS", "LICENSE_PLATE", "PASSPORT", "API_KEY", "SOCIAL_SECURITY");

    public DesensitizationVerifier(DesensitizationRuleProperties ruleProperties,
            TextSensitiveDetectionService detectionService) {
        this.ruleProperties = ruleProperties;
        this.detectionService = detectionService;
    }

    /**
     * 执行双重校验。
     *
     * @param originalText     脱敏前明文
     * @param desensitizedText 脱敏后文本
     * @param entities         首轮识别出的敏感实体
     * @param language         语言（zh/en/mixed），用于二次扫描
     * @return 校验结果（永不返回 null）
     */
    public VerificationResult verify(String originalText, String desensitizedText, List<SensitiveEntity> entities,
            String language) {
        if (!ruleProperties.isVerifyEnabled()) {
            return VerificationResult.disabled();
        }
        List<SensitiveEntity> expected = entities == null ? List.of() : entities;

        // 校验1：明文残留扫描
        List<String> residualTexts = expected.stream()
                .filter(e -> e.getOriginalText() != null && !e.getOriginalText().isEmpty())
                .filter(e -> desensitizedText != null && desensitizedText.contains(e.getOriginalText()))
                .map(SensitiveEntity::getOriginalText)
                .distinct()
                .collect(Collectors.toList());

        // 校验2：整体二次扫描（发现首轮漏识别但脱敏后仍残留的模式）
        List<String> reDetectedTexts = reScanForResidual(desensitizedText, language);

        int total = expected.size();
        int removed = total - residualTexts.size();
        double coverage = total == 0 ? 1.0 : (double) removed / total;

        boolean belowThreshold = coverage < ruleProperties.getCoverageThreshold();
        boolean hasResidual = !residualTexts.isEmpty() || !reDetectedTexts.isEmpty();
        boolean needsManualReview = hasResidual || belowThreshold;

        ManualReviewTask task = null;
        if (needsManualReview && ruleProperties.isManualReviewEnabled()) {
            task = new ManualReviewTask(originalText, desensitizedText, residualTexts, reDetectedTexts, coverage);
            enqueue(task);
        }

        if (needsManualReview) {
            log.warn("脱敏结果未通过算法校验：覆盖率={}, 明文残留={}, 二次扫描残留={}",
                    String.format("%.2f", coverage), residualTexts, reDetectedTexts);
        } else {
            log.info("脱敏结果通过算法校验：覆盖率={}（阈值={}）", String.format("%.2f", coverage),
                    ruleProperties.getCoverageThreshold());
        }

        return new VerificationResult(true, coverage, residualTexts, reDetectedTexts, needsManualReview,
                task != null, task != null ? task.getReviewId() : null);
    }

    private List<String> reScanForResidual(String desensitizedText, String language) {
        if (desensitizedText == null || desensitizedText.isBlank() || detectionService == null) {
            return List.of();
        }
        try {
            // 屏蔽脱敏占位符区域本身，避免占位符内容被二次扫描误判为残留
            String maskedInput = desensitizedText.replaceAll("\\[[^\\]]+\\]", " ");
            List<SensitiveEntity> reDetected = detectionService.detectSensitiveInfo(maskedInput, language,
                    RESCAN_TYPES, null);
            return reDetected.stream()
                    .filter(e -> e.getOriginalText() != null && e.getOriginalText().length() >= 2)
                    .map(SensitiveEntity::getOriginalText)
                    .filter(text -> !isPlaceholder(text))
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("二次扫描执行失败，降级为仅明文残留校验: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean isPlaceholder(String text) {
        return text.matches(PLACEHOLDER_PATTERN) || text.contains("*");
    }

    private void enqueue(ManualReviewTask task) {
        if (reviewQueue.size() >= MAX_QUEUE_SIZE) {
            reviewQueue.poll();
        }
        reviewQueue.offer(task);
        log.info("人工复核任务已入队：reviewId={}, 覆盖率={}", task.getReviewId(), String.format("%.2f", task.getCoverage()));
    }

    /** 查看当前待人工复核的任务（按入队顺序） */
    public List<ManualReviewTask> getPendingReviews() {
        return new ArrayList<>(reviewQueue);
    }

    /** 待复核任务数 */
    public int pendingReviewCount() {
        return reviewQueue.size();
    }

    /** 清除已处理的任务 */
    public void removeReview(String reviewId) {
        reviewQueue.removeIf(task -> task.getReviewId().equals(reviewId));
    }

    // ========== 校验结果 ==========

    @Getter
    public static class VerificationResult {
        private final boolean enabled;
        private final double coverage;
        private final List<String> residualTexts;
        private final List<String> reDetectedTexts;
        private final boolean needsManualReview;
        private final boolean enqueued;
        private final String reviewId;

        private VerificationResult(boolean enabled, double coverage, List<String> residualTexts,
                List<String> reDetectedTexts, boolean needsManualReview, boolean enqueued, String reviewId) {
            this.enabled = enabled;
            this.coverage = coverage;
            this.residualTexts = residualTexts == null ? List.of() : residualTexts;
            this.reDetectedTexts = reDetectedTexts == null ? List.of() : reDetectedTexts;
            this.needsManualReview = needsManualReview;
            this.enqueued = enqueued;
            this.reviewId = reviewId;
        }

        public static VerificationResult disabled() {
            return new VerificationResult(false, 1.0, List.of(), List.of(), false, false, null);
        }

        /** 脱敏覆盖率 = 已成功消除明文的实体数 / 检测实体总数（1.0 表示无残留） */
        public boolean isEnabled() {
            return enabled;
        }

        /** 是否触发人工复核（存在明文残留 / 二次扫描残留 / 覆盖率低于阈值） */
        public boolean needsManualReview() {
            return needsManualReview;
        }

        public boolean isPassed() {
            return !needsManualReview;
        }
    }

    // ========== 人工复核任务 ==========

    @Getter
    public static class ManualReviewTask {
        private final String reviewId;
        private final String originalText;
        private final String desensitizedText;
        private final List<String> residualTexts;
        private final List<String> reDetectedTexts;
        private final double coverage;
        private final LocalDateTime createdAt;
        private volatile ReviewStatus status;

        public ManualReviewTask(String originalText, String desensitizedText, List<String> residualTexts,
                List<String> reDetectedTexts, double coverage) {
            this.reviewId = "RVW-" + TS.format(LocalDateTime.now()) + "-" + REVIEW_SEQ.getAndIncrement();
            this.originalText = originalText;
            this.desensitizedText = desensitizedText;
            this.residualTexts = residualTexts == null ? List.of() : residualTexts;
            this.reDetectedTexts = reDetectedTexts == null ? List.of() : reDetectedTexts;
            this.coverage = coverage;
            this.createdAt = LocalDateTime.now();
            this.status = ReviewStatus.PENDING;
        }

        public void markResolved() {
            this.status = ReviewStatus.RESOLVED;
        }

        public void markDismissed() {
            this.status = ReviewStatus.DISMISSED;
        }
    }

    public enum ReviewStatus {
        PENDING, RESOLVED, DISMISSED
    }
}
