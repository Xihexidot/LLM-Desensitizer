package com.hdu.apisensitivities.repository;

import com.hdu.apisensitivities.entity.gateway.GatewayAuditEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface GatewayAuditRepository {

    void save(GatewayAuditEvent event);

    void updateUserAction(String eventId, String userAction);

    List<GatewayAuditEvent> query(String appId, String userId, String decisionAction, int limit);

    Map<String, Object> getStats();

    Optional<GatewayAuditEvent> findById(String eventId);
}
