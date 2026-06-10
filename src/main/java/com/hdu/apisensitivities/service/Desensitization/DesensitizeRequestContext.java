package com.hdu.apisensitivities.service.Desensitization;

/**
 * 线程上下文工具，用于无侵入地在同一个请求线程里共享 sessionId
 */
public class DesensitizeRequestContext {
    private static final ThreadLocal<String> currentSessionId = new ThreadLocal<>();

    public static void setSessionId(String sessionId) {
        currentSessionId.set(sessionId);
    }

    public static String getSessionId() {
        return currentSessionId.get();
    }

    public static void clear() {
        currentSessionId.remove();
    }
}