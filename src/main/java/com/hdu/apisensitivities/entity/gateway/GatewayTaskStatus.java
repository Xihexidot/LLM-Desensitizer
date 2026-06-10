package com.hdu.apisensitivities.entity.gateway;

public enum GatewayTaskStatus {
    PENDING,
    PARSING,
    DETECTING,
    DESENSITIZING,
    ROUTING,
    COMPLETED,
    FAILED,
    BLOCKED,
    WAITING_APPROVAL
}
