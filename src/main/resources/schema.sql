CREATE TABLE IF NOT EXISTS sensitive_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pattern_name VARCHAR(255),
    regex VARCHAR(255),
    is_enabled BOOLEAN,
    description VARCHAR(255),
    rule_type VARCHAR(16) DEFAULT 'CUSTOM',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 内置规则默认值（若 DB 无覆盖项则走 PatternRegistry 代码默认值）
-- 可通过企业管理后台按 pattern_name 覆盖 BUILTIN 规则的 regex
-- INSERT INTO sensitive_rules (pattern_name, regex, is_enabled, description, rule_type)
-- VALUES ('PHONE_NUMBER', '重写的手机号正则', true, '企业自定义手机号格式', 'BUILTIN');

-- 敏感词典表（白名单/黑名单统一管理）
CREATE TABLE IF NOT EXISTS sensitive_dict (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dict_type VARCHAR(32) NOT NULL COMMENT '词典类型: SURNAME_WHITELIST / PERSON_BLACKLIST / ADDRESS_BLACKLIST / ADDRESS_SUFFIXES',
    term VARCHAR(64) NOT NULL COMMENT '词条内容',
    is_enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_type_term (dict_type, term)
);

-- 网关审计事件表
CREATE TABLE IF NOT EXISTS gateway_audit_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id    VARCHAR(64)  NOT NULL UNIQUE,
    timestamp   TIMESTAMP    NOT NULL,
    tenant_id   VARCHAR(64),
    app_id      VARCHAR(64),
    user_id     VARCHAR(64),
    department  VARCHAR(64),
    channel     VARCHAR(32),
    request_type VARCHAR(32),
    target_provider VARCHAR(32),
    scene_code  VARCHAR(32),
    matched_sensitive_types VARCHAR(512),
    decision_action VARCHAR(32),
    input_risk_level  VARCHAR(16),
    output_risk_level VARCHAR(16),
    user_action  VARCHAR(32) COMMENT '插件侧用户确认: DESENSITIZE_AND_SEND|SEND_ORIGINAL|CANCEL|AUTO',
    original_content TEXT COMMENT '原始输入内容（供安全审计审查）',
    processed_content TEXT COMMENT '脱敏后内容',
    request_hash  VARCHAR(128),
    response_hash VARCHAR(128),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_timestamp (timestamp),
    INDEX idx_channel (channel)
);

-- =======================================================
-- 预置演示数据（比赛评审用 — 打开仪表盘即有数据可看）
-- =======================================================
INSERT INTO gateway_audit_event (event_id, timestamp, user_id, department, channel, request_type, target_provider, matched_sensitive_types, decision_action, input_risk_level, output_risk_level, user_action, original_content, processed_content, request_hash)
SELECT *
FROM (
  -- 1) 客服部张三 — DeepSeek — 手机号+身份证 → MEDIUM，脱敏后发送
  SELECT 'evt-demo-001' AS event_id, DATEADD('MINUTE', -30, CURRENT_TIMESTAMP) AS timestamp, 'user-m4k7x2p9' AS user_id, '客服部' AS department, 'BROWSER_PLUGIN' AS channel, 'PLUGIN_CHECK' AS request_type, 'DeepSeek' AS target_provider, 'PHONE_NUMBER,ID_CARD' AS matched_sensitive_types, 'DESENSITIZE_AND_ALLOW' AS decision_action, 'MEDIUM' AS input_risk_level, 'NONE' AS output_risk_level, 'DESENSITIZE_AND_SEND' AS user_action, '请帮我整理客户张三的资料，手机号13812345678，身份证330102199901011234' AS original_content, '请帮我整理客户[NAME_1]的资料，手机号[PHONE_1]，身份证[ID_CARD_1]' AS processed_content, 'h1' AS request_hash
  UNION ALL
  -- 2) 研发部李四 — ChatGPT — API_KEY+PASSWORD → HIGH，用户取消
  SELECT 'evt-demo-002', DATEADD('MINUTE', -25, CURRENT_TIMESTAMP), 'user-r8f3k9', '研发部', 'BROWSER_PLUGIN', 'PLUGIN_CHECK', 'ChatGPT', 'API_KEY,PASSWORD', 'BLOCK', 'HIGH', 'NONE', 'CANCEL', '帮我调试这个接口，API Key 是 sk-abc123def456，密码是 admin123', '帮我调试这个接口，API Key 是 [API_KEY_1]，密码是 [PASSWORD_1]', 'h2'
  UNION ALL
  -- 3) 市场部王五 — Kimi — PHONE_NUMBER → LOW，发送脱敏版
  SELECT 'evt-demo-003', DATEADD('MINUTE', -22, CURRENT_TIMESTAMP), 'user-p1z7v3', '市场部', 'BROWSER_PLUGIN', 'PLUGIN_CHECK', 'Kimi', 'PHONE_NUMBER', 'DESENSITIZE_AND_ALLOW', 'LOW', 'NONE', 'DESENSITIZE_AND_SEND', '这段推广文案里加上联系方式：电话17600123456', '这段推广文案里加上联系方式：电话[PHONE_1]', 'h3'
  UNION ALL
  -- 4) 客服部张三 — 豆包 — ADDRESS+PERSON_NAME → MEDIUM，发送原文
  SELECT 'evt-demo-004', DATEADD('MINUTE', -18, CURRENT_TIMESTAMP), 'user-m4k7x2p9', '客服部', 'BROWSER_PLUGIN', 'PLUGIN_CHECK', '豆包', 'ADDRESS,PERSON_NAME', 'DESENSITIZE_AND_ALLOW', 'MEDIUM', 'NONE', 'SEND_ORIGINAL', '收货地址：浙江省杭州市西湖区文三路500号，收件人赵六', '收货地址：[ADDRESS_1]，收件人[NAME_1]', 'h4'
  UNION ALL
  -- 5) 金融部赵六 — DeepSeek — 银行卡+身份证 → HIGH，阻断
  SELECT 'evt-demo-005', DATEADD('MINUTE', -15, CURRENT_TIMESTAMP), 'user-q2w8m1', '金融部', 'BROWSER_PLUGIN', 'PLUGIN_CHECK', 'DeepSeek', 'BANK_CARD,ID_CARD', 'BLOCK', 'HIGH', 'NONE', 'CANCEL', '客户转账：工行卡号6222021234567890123，身份证320501198502031234', '客户转账：工行卡号[BANK_CARD_1]，身份证[ID_CARD_1]', 'h5'
  UNION ALL
  -- 6) API调用 — 研发部 — MEDIUM
  SELECT 'evt-demo-006', DATEADD('MINUTE', -12, CURRENT_TIMESTAMP), 'dev-api-client', '研发部', 'backend-api', 'CHAT', 'DeepSeek', 'EMAIL,PHONE_NUMBER', 'DESENSITIZE_AND_ALLOW', 'MEDIUM', 'LOW', 'AUTO', '用户的联系方式是zhangsan@example.com，电话13900000001', '用户的联系方式是[EMAIL_1]，电话[PHONE_1]', 'h6'
  UNION ALL
  -- 7) API调用 — 客服部 — LOW
  SELECT 'evt-demo-007', DATEADD('MINUTE', -8, CURRENT_TIMESTAMP), 'evt-app-cs', '客服部', 'backend-api', 'CHAT', '通义千问', 'PHONE_NUMBER', 'ALLOW', 'LOW', 'NONE', 'AUTO', '请帮我查询订单 #123456 的物流状态', '请帮我查询订单 #123456 的物流状态', 'h7'
  UNION ALL
  -- 8) 市场部王五 — ChatGPT — ADDRESS+PERSON_NAME → MEDIUM，取消
  SELECT 'evt-demo-008', DATEADD('MINUTE', -5, CURRENT_TIMESTAMP), 'user-p1z7v3', '市场部', 'BROWSER_PLUGIN', 'PLUGIN_CHECK', 'ChatGPT', 'ADDRESS,PERSON_NAME', 'BLOCK', 'MEDIUM', 'NONE', 'CANCEL', '帮我分析这个区域的客户分布：北京市海淀区中关村大街1号，负责人周八', '帮我分析这个区域的客户分布：[ADDRESS_1]，负责人[NAME_1]', 'h8'
  UNION ALL
  -- 9) API调用 — 研发部 — ID_CARD → HIGH
  SELECT 'evt-demo-009', DATEADD('MINUTE', -3, CURRENT_TIMESTAMP), 'dev-api-client', '研发部', 'backend-api', 'CHAT', 'DeepSeek', 'ID_CARD', 'BLOCK', 'HIGH', 'NONE', 'AUTO', '测试用户身份证号：440101199912311234', '测试用户身份证号：[ID_CARD_1]', 'h9'
  UNION ALL
  -- 10) 财务部陈九 — Kimi — BANK_CARD → HIGH，取消
  SELECT 'evt-demo-010', DATEADD('MINUTE', -1, CURRENT_TIMESTAMP), 'user-k5v9s2', '财务部', 'BROWSER_PLUGIN', 'PLUGIN_CHECK', 'Kimi', 'BANK_CARD', 'BLOCK', 'HIGH', 'NONE', 'CANCEL', '付款账号：招行6217001234567890，请核对', '付款账号：招行[BANK_CARD_1]，请核对', 'h10'
) AS t
WHERE NOT EXISTS (SELECT 1 FROM gateway_audit_event WHERE event_id = t.event_id);
