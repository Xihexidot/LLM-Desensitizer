package com.hdu.apisensitivities.service.gateway;

import com.hdu.apisensitivities.entity.LlmProvider;
import com.hdu.apisensitivities.entity.LlmResponse;
import com.hdu.apisensitivities.entity.gateway.GatewayAuditEvent;
import com.hdu.apisensitivities.entity.gateway.GatewayFileTaskInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface EnterpriseGatewayApplicationService {
    /** OpenAI 兼容端点核心处理：检测 → 脱敏 → 调LLM → 审计 */
    Map<String, Object> processChatCompletions(String content, LlmProvider provider, Map<String, String> headers);

    GatewayFileTaskInfo createFileTask(String fileName, String sceneCode, String userId, String department,
            String tenantId, String appId);

    Optional<GatewayFileTaskInfo> getFileTask(String taskId);

    List<GatewayAuditEvent> queryAuditEvents(String appId, String userId, String decisionAction);

    /** 从 model 名称映射 LlmProvider */
    static LlmProvider resolveProviderFromModel(String model) {
        if (model == null)
            return LlmProvider.DEEPSEEK;
        String m = model.toLowerCase();
        if (m.contains("deepseek"))
            return LlmProvider.DEEPSEEK;
        if (m.contains("gpt") || m.contains("openai"))
            return LlmProvider.OPENAI;
        if (m.contains("doubao") || m.contains("豆包"))
            return LlmProvider.DOUBAO;
        if (m.contains("qwen") || m.contains("通义"))
            return LlmProvider.QWEN;
        if (m.contains("kimi") || m.contains("moonshot"))
            return LlmProvider.KIMI;
        if (m.contains("claude"))
            return LlmProvider.CLAUDE;
        if (m.contains("hunyuan") || m.contains("混元"))
            return LlmProvider.HUNYUAN;
        if (m.contains("ollama"))
            return LlmProvider.OLLAMA;
        return LlmProvider.DEEPSEEK;
    }

    /** 从 OpenAI messages 数组提取拼接后的 content 字符串 */
    static String extractContentFromMessages(Object messagesObj) {
        if (!(messagesObj instanceof List<?> messages))
            return "";
        StringBuilder sb = new StringBuilder();
        for (Object msg : messages) {
            if (msg instanceof Map<?, ?> m) {
                Object role = m.get("role");
                Object content = m.get("content");
                if (content instanceof String s && !s.isBlank()) {
                    if (sb.length() > 0)
                        sb.append("\n");
                    sb.append(s);
                }
            }
        }
        return sb.toString();
    }
}
