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
