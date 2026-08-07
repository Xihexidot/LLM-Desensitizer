/**
 * 脱敏解码工具：将 AI 返回内容中的脱敏标记精准还原为原始业务数据。
 *
 * 背景：
 * 前端将用户原始信息脱敏后发送至后端 AI 接口，AI 答复中可能引用输入中的脱敏标记
 * （如 "[PHONE_1]"、"[MASKED_1]"、"[138****1234_1]"）。本模块负责把这些标记
 * 完整还原为发送前的原始数据格式。
 *
 * 数据源（推荐）：后端 /desensitize/text 响应中的 maskMapping（占位符 → 明文），
 * 该映射由后端会话级缓存 GlobalSessionContextRepository 反向导出，与前端会话一一对应。
 *
 * 兜底（可选）：当 maskMapping 缺失时，可基于脱敏文本与 detectedEntities 重建映射：
 * 后端按"类型"独立分配序号（[PHONE_1] 为该会话第 1 个手机号），同类型实体按原文位置
 * 从后往前（start 降序）分配序号，据此将实体明文与占位符一一对应。
 */

// 匹配 "[标记_序号]" 形式的脱敏标记，如 [PHONE_1]、[MASKED_1]、[138****1234_1]
const MASK_TOKEN_REGEX = /\[([^\[\]]+)_(\d+)\]/g;
// 匹配任意 "[...]" 片段（用于解码替换时逐段查表，未命中保持原样）
const ANY_BRACKET_REGEX = /\[[^\[\]]+\]/g;

// SensitiveType 枚举名 → Mask 策略占位符标识名（后端 MASK_TEMPLATES）
const TYPE_TOKEN_NAME = {
  PHONE_NUMBER: 'PHONE',
  BANK_CARD: 'BANK_CARD',
  EMAIL: 'EMAIL',
  ID_CARD: 'ID_CARD',
  NAME: 'NAME',
  PERSON: 'PERSON',
  ADDRESS: 'ADDRESS',
  ORGANIZATION: 'ORG',
  CREDIT_CARD: 'CREDIT_CARD',
  PASSWORD: 'PASSWORD',
  API_KEY: 'API_KEY',
  PASSPORT: 'PASSPORT',
  BIRTH_DATE: 'BIRTH_DATE',
  CUSTOM: 'CUSTOM',
  IP_ADDRESS: 'IP',
  LICENSE_PLATE: 'PLATE',
  // 无专用模板的实体在 Mask 策略中兜底为 [MASKED_1]
  SOCIAL_SECURITY: 'MASKED',
};

/**
 * 解码文本：将文本中所有出现在映射表中的脱敏标记替换为原始业务数据。
 *
 * 仅替换 maskMapping 中存在的标记；未匹配的 "[...]" 片段（如 AI 自产的系统提示）
 * 保持原样，避免误替换。
 *
 * @param {string} text AI 返回的待解码文本
 * @param {Record<string,string>} maskMapping 脱敏标记 → 明文 映射
 * @returns {string} 解码后的文本
 */
export function decodeText(text, maskMapping) {
  if (!text || !maskMapping || Object.keys(maskMapping).length === 0) {
    return text;
  }
  return text.replace(ANY_BRACKET_REGEX, (token) => maskMapping[token] ?? token);
}

/**
 * 基于脱敏结果重建"占位符 → 明文"映射（后端未返回 maskMapping 时的兜底方案）。
 *
 * 算法：
 * 1. 从脱敏文本提取所有 "[标记_序号]" 占位符，按"标记标识"分组；
 * 2. 将实体（detectedEntities）按类型分组，同类型内按 start 降序、按明文去重
 *    （同一明文在会话中复用一个占位符）；
 * 3. 已知类型的占位符用类型标识匹配实体组，序号 i 对应降序列表第 i 个明文；
 * 4. 无法映射到已知类型的占位符（如部分脱敏值、MASKED 兜底）归入未知组，
 *    与剩余未用实体按 start 降序全局匹配。
 *
 * @param {string} desensitizedText 脱敏后的文本（含标记）
 * @param {Array<{type:string, originalText:string, start:number, end:number}>} entities 检测到的敏感实体
 * @returns {Record<string,string>} 占位符 → 明文
 */
export function buildMaskMapping(desensitizedText, entities) {
  const mapping = {};
  if (!desensitizedText || !Array.isArray(entities) || entities.length === 0) {
    return mapping;
  }

  // 1. 提取占位符
  const tokens = [];
  let match;
  MASK_TOKEN_REGEX.lastIndex = 0;
  while ((match = MASK_TOKEN_REGEX.exec(desensitizedText)) !== null) {
    tokens.push({ token: match[0], label: match[1], seq: Number(match[2]) });
  }
  if (tokens.length === 0) {
    return mapping;
  }

  // 实体按 start 降序预处理
  const sortedEntities = [...entities]
    .filter((e) => e && e.originalText != null && typeof e.start === 'number')
    .sort((a, b) => b.start - a.start);

  // 按类型分组（枚举名 → 实体列表），同类型内按明文去重保持首次出现顺序
  const entityByType = new Map();
  for (const e of sortedEntities) {
    const typeKey = e.type;
    if (!entityByType.has(typeKey)) {
      entityByType.set(typeKey, []);
    }
    const list = entityByType.get(typeKey);
    if (!list.some((item) => item.originalText === e.originalText)) {
      list.push(e);
    }
  }

  // 2. 按占位符标识分组
  const tokenByLabel = new Map();
  for (const t of tokens) {
    if (!tokenByLabel.has(t.label)) {
      tokenByLabel.set(t.label, []);
    }
    tokenByLabel.get(t.label).push(t);
  }

  const usedEntities = new Set();

  // 3. 已知类型占位符：类型标识匹配实体组
  for (const [enumType, tokenName] of Object.entries(TYPE_TOKEN_NAME)) {
    const typeTokens = tokenByLabel.get(tokenName);
    if (!typeTokens || typeTokens.length === 0) {
      continue;
    }
    const typeEntities = entityByType.get(enumType);
    if (!typeEntities || typeEntities.length === 0) {
      continue;
    }
    // 该类型的占位符序号按升序分配 → 降序实体列表按序号取值
    typeTokens.sort((a, b) => a.seq - b.seq);
    for (const t of typeTokens) {
      const entity = typeEntities[t.seq - 1];
      if (entity) {
        mapping[t.token] = entity.originalText;
        usedEntities.add(entity);
      }
    }
  }

  // 4. 未知类型占位符（MASKED / 部分脱敏值等）：与剩余未用实体全局降序匹配
  const remainingTokens = [];
  for (const tokensOfLabel of tokenByLabel.values()) {
    for (const t of tokensOfLabel) {
      if (!mapping[t.token]) {
        remainingTokens.push(t);
      }
    }
  }
  if (remainingTokens.length > 0) {
    remainingTokens.sort((a, b) => a.seq - b.seq);
    const unusedEntities = sortedEntities.filter((e) => !usedEntities.has(e));
    for (const t of remainingTokens) {
      const entity = unusedEntities[t.seq - 1];
      if (entity) {
        mapping[t.token] = entity.originalText;
      }
    }
  }

  return mapping;
}
