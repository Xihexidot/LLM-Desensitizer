package com.hdu.apisensitivities.entity.gateway;

public enum GatewayDecisionAction {
    ALLOW,
    DESENSITIZE_AND_ALLOW,
    ROUTE_TO_INTERNAL_MODEL,
    BLOCK,
    REQUIRE_APPROVAL,
    ASYNC_REVIEW
}
