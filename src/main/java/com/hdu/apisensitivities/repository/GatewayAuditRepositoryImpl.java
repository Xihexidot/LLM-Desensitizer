package com.hdu.apisensitivities.repository;

import com.hdu.apisensitivities.entity.gateway.GatewayAuditEvent;
import com.hdu.apisensitivities.entity.gateway.GatewayDecisionAction;
import com.hdu.apisensitivities.entity.gateway.GatewayRiskLevel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class GatewayAuditRepositoryImpl implements GatewayAuditRepository {

        private final JdbcTemplate jdbcTemplate;

        public GatewayAuditRepositoryImpl(JdbcTemplate jdbcTemplate) {
                this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public void save(GatewayAuditEvent event) {
                String sql = "INSERT INTO gateway_audit_event (event_id, timestamp, tenant_id, app_id, user_id, department, "
                                + "channel, request_type, target_provider, scene_code, matched_sensitive_types, "
                                + "decision_action, input_risk_level, output_risk_level, user_action, "
                                + "original_content, processed_content, request_hash, response_hash) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                jdbcTemplate.update(sql,
                                event.getEventId(),
                                Timestamp.from(event.getTimestamp() != null ? event.getTimestamp() : Instant.now()),
                                event.getTenantId(),
                                event.getAppId(),
                                event.getUserId(),
                                event.getDepartment(),
                                event.getChannel(),
                                event.getRequestType(),
                                event.getTargetProvider(),
                                event.getSceneCode(),
                                joinTypes(event.getMatchedSensitiveTypes()),
                                event.getDecisionAction() != null ? event.getDecisionAction().name() : null,
                                event.getInputRiskLevel() != null ? event.getInputRiskLevel().name() : null,
                                event.getOutputRiskLevel() != null ? event.getOutputRiskLevel().name() : null,
                                null, // userAction 初始为空
                                event.getOriginalContent(),
                                event.getProcessedContent(),
                                event.getRequestHash(),
                                event.getResponseHash());
        }

        @Override
        public void updateUserAction(String eventId, String userAction) {
                jdbcTemplate.update(
                                "UPDATE gateway_audit_event SET user_action = ? WHERE event_id = ?",
                                userAction, eventId);
        }

        @Override
        public List<GatewayAuditEvent> query(String appId, String userId, String decisionAction, int limit) {
                StringBuilder sql = new StringBuilder(
                                "SELECT * FROM gateway_audit_event WHERE 1=1 ");
                java.util.List<Object> params = new java.util.ArrayList<>();

                if (appId != null && !appId.isBlank()) {
                        sql.append("AND app_id = ? ");
                        params.add(appId);
                }
                if (userId != null && !userId.isBlank()) {
                        sql.append("AND user_id = ? ");
                        params.add(userId);
                }
                if (decisionAction != null && !decisionAction.isBlank()) {
                        sql.append("AND decision_action = ? ");
                        params.add(decisionAction);
                }

                sql.append("ORDER BY timestamp DESC LIMIT ?");
                params.add(limit);

                return jdbcTemplate.query(sql.toString(), params.toArray(), new AuditEventRowMapper());
        }

        @Override
        public Map<String, Object> getStats() {
                Map<String, Object> stats = new HashMap<>();

                // 今日总数
                String today = LocalDate.now().toString();
                Integer todayTotal = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM gateway_audit_event WHERE timestamp >= ?",
                                Integer.class, today);
                stats.put("todayTotal", todayTotal != null ? todayTotal : 0);

                // 按渠道分布
                List<Map<String, Object>> byChannel = jdbcTemplate.queryForList(
                                "SELECT channel, COUNT(*) as cnt FROM gateway_audit_event "
                                                + "WHERE timestamp >= ? GROUP BY channel",
                                today);
                stats.put("byChannel", byChannel);

                // 按风险等级分布（输入侧）
                List<Map<String, Object>> byRiskLevel = jdbcTemplate.queryForList(
                                "SELECT input_risk_level, COUNT(*) as cnt FROM gateway_audit_event "
                                                + "WHERE timestamp >= ? GROUP BY input_risk_level",
                                today);
                stats.put("byRiskLevel", byRiskLevel);

                // 按用户操作分布
                List<Map<String, Object>> byUserAction = jdbcTemplate.queryForList(
                                "SELECT user_action, COUNT(*) as cnt FROM gateway_audit_event "
                                                + "WHERE timestamp >= ? AND user_action IS NOT NULL GROUP BY user_action",
                                today);
                stats.put("byUserAction", byUserAction);

                // 按决策动作分布
                List<Map<String, Object>> byDecision = jdbcTemplate.queryForList(
                                "SELECT decision_action, COUNT(*) as cnt FROM gateway_audit_event "
                                                + "WHERE timestamp >= ? AND decision_action IS NOT NULL GROUP BY decision_action",
                                today);
                stats.put("byDecision", byDecision);

                // 按目标LLM供应商分布（外部调用统计）
                List<Map<String, Object>> byTargetProvider = jdbcTemplate.queryForList(
                                "SELECT target_provider, COUNT(*) as cnt FROM gateway_audit_event "
                                                + "WHERE timestamp >= ? AND target_provider IS NOT NULL GROUP BY target_provider",
                                today);
                stats.put("byTargetProvider", byTargetProvider);

                return stats;
        }

        @Override
        public Optional<GatewayAuditEvent> findById(String eventId) {
                List<GatewayAuditEvent> list = jdbcTemplate.query(
                                "SELECT * FROM gateway_audit_event WHERE event_id = ?",
                                new Object[] { eventId },
                                new AuditEventRowMapper());
                return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }

        private String joinTypes(List<String> types) {
                if (types == null || types.isEmpty()) {
                        return null;
                }
                return String.join(",", types);
        }

        private static class AuditEventRowMapper implements RowMapper<GatewayAuditEvent> {
                @Override
                public GatewayAuditEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
                        Timestamp ts = rs.getTimestamp("timestamp");
                        String types = rs.getString("matched_sensitive_types");
                        List<String> matchedTypes = (types != null && !types.isBlank())
                                        ? Arrays.asList(types.split(","))
                                        : Collections.emptyList();

                        String da = rs.getString("decision_action");
                        String irl = rs.getString("input_risk_level");
                        String orl = rs.getString("output_risk_level");

                        return GatewayAuditEvent.builder()
                                        .eventId(rs.getString("event_id"))
                                        .timestamp(ts != null ? ts.toInstant() : null)
                                        .tenantId(rs.getString("tenant_id"))
                                        .appId(rs.getString("app_id"))
                                        .userId(rs.getString("user_id"))
                                        .department(rs.getString("department"))
                                        .channel(rs.getString("channel"))
                                        .requestType(rs.getString("request_type"))
                                        .targetProvider(rs.getString("target_provider"))
                                        .sceneCode(rs.getString("scene_code"))
                                        .matchedSensitiveTypes(matchedTypes)
                                        .decisionAction(da != null ? GatewayDecisionAction.valueOf(da) : null)
                                        .inputRiskLevel(irl != null ? GatewayRiskLevel.valueOf(irl) : null)
                                        .outputRiskLevel(orl != null ? GatewayRiskLevel.valueOf(orl) : null)
                                        .userAction(rs.getString("user_action"))
                                        .originalContent(rs.getString("original_content"))
                                        .processedContent(rs.getString("processed_content"))
                                        .build();
                }
        }
}
