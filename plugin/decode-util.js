/**
 * AI 输入安全助手 —— 脱敏解码工具
 *
 * 用于将 AI 返回内容中的脱敏标记（如 [PHONE_1]、[MASKED_1]、[138****1234_1]）
 * 精准还原为发送前的原始业务数据，实现"脱敏发送 → AI 答复 → 一键复原"闭环。
 *
 * 数据源：后端 /plugin/audit-check 响应中的 maskMapping（占位符 → 明文），
 * 该映射由后端会话级缓存 GlobalSessionContextRepository 反向导出，
 * 与脱敏内容一一对应，保证脱敏与复原的数据一致性。
 *
 * 兼容性：UMD —— 在浏览器全局暴露 AIGuardDecode；在 Node（插件 E2E）以 CJS 导出。
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }
  root.AIGuardDecode = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  // 匹配 "[标记_序号]" 形式的脱敏标记，如 [PHONE_1]、[MASKED_1]、[138****1234_1]
  const MASK_TOKEN_REGEX = /\[([^\[\]]+)_(\d+)\]/g;
  // 匹配任意 "[...]" 片段（解码时逐段查表，未命中保持原样，避免误替换）
  const ANY_BRACKET_REGEX = /\[[^\[\]]+\]/g;

  // SensitiveType 枚举名 → Mask 策略占位符标识名（与后端 MASK_TEMPLATES 一致）
  const TYPE_TOKEN_NAME = {
    PHONE_NUMBER: "PHONE",
    BANK_CARD: "BANK_CARD",
    EMAIL: "EMAIL",
    ID_CARD: "ID_CARD",
    NAME: "NAME",
    PERSON: "PERSON",
    ADDRESS: "ADDRESS",
    ORGANIZATION: "ORG",
    CREDIT_CARD: "CREDIT_CARD",
    PASSWORD: "PASSWORD",
    API_KEY: "API_KEY",
    PASSPORT: "PASSPORT",
    BIRTH_DATE: "BIRTH_DATE",
    CUSTOM: "CUSTOM",
    IP_ADDRESS: "IP",
    LICENSE_PLATE: "PLATE",
    // 无专用模板的实体在 Mask 策略中兜底为 [MASKED_1]
    SOCIAL_SECURITY: "MASKED",
  };

  /**
   * 解码文本：将文本中所有出现在映射表中的脱敏标记替换为原始业务数据。
   * 未匹配的 "[...]" 片段（如 AI 自产系统提示）保持原样。
   *
   * @param {string} text 待解码文本
   * @param {Record<string,string>} maskMapping 脱敏标记 → 明文 映射
   * @returns {string} 解码后的文本
   */
  function decodeText(text, maskMapping) {
    if (!text || !maskMapping || Object.keys(maskMapping).length === 0) {
      return text;
    }
    return text.replace(
      ANY_BRACKET_REGEX,
      (token) => maskMapping[token] ?? token,
    );
  }

  /**
   * 解码并生成高亮 HTML：被替换的脱敏标记用 <mark> 高亮，供复原面板展示。
   * 所有动态内容一律 HTML 转义，防止注入。
   *
   * 从 v2 起按"行"处理并叠加行级噪声过滤：
   * - 含被还原脱敏标记的行必然保留（可能是核心对话）；
   * - 其余行解码后经 NOISE_LINE_PATTERNS 判断，命中系统提示 / 功能按钮 /
   *   时间标记 / 脱敏提示等噪声整行剔除，避免一键复原混入侧边栏等无关内容。
   *
   * @param {string} text 待解码文本
   * @param {Record<string,string>} maskMapping 脱敏标记 → 明文 映射
   * @returns {{text: string, html: string, replacedCount: number, replaced: Array<{token:string, plain:string}>}}
   */
  function decodeWithHighlights(text, maskMapping) {
    const result = {
      text: text || "",
      html: "",
      replacedCount: 0,
      replaced: [],
    };
    if (!text) return result;
    const mapping =
      maskMapping && typeof maskMapping === "object" ? maskMapping : {};
    const hasMapping = Object.keys(mapping).length > 0;
    const htmlLines = [];
    const textLines = [];
    const lines = String(text).split(/\r?\n/);
    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (!line) continue;
      const decodedLine = hasMapping ? decodeText(line, mapping) : line;
      // 含被还原标记的行必然保留（可能是核心对话）；
      // 其余行先解码再按整行做噪声过滤（防明文被正则误判）。
      if (
        (!hasMapping || !containsMaskedToken(line, mapping)) &&
        isNoiseLine(decodedLine)
      ) {
        continue;
      }
      htmlLines.push(
        hasMapping ? highlightLine(line, mapping, result) : escapeHtml(line),
      );
      textLines.push(decodedLine);
    }
    result.html = htmlLines.join("\n");
    result.text = textLines.join("\n");
    return result;
  }

  /** 单行内替换脱敏标记并返回高亮 HTML（累加 result.replacedCount / result.replaced） */
  function highlightLine(line, maskMapping, result) {
    let html = "";
    let lastIndex = 0;
    ANY_BRACKET_REGEX.lastIndex = 0;
    let m;
    while ((m = ANY_BRACKET_REGEX.exec(line)) !== null) {
      const token = m[0];
      html += escapeHtml(line.slice(lastIndex, m.index));
      const plain = maskMapping[token];
      if (plain !== undefined) {
        html += "<mark>" + escapeHtml(plain) + "</mark>";
        result.replaced.push({ token, plain });
        result.replacedCount++;
      } else {
        html += escapeHtml(token);
      }
      lastIndex = m.index + token.length;
    }
    html += escapeHtml(line.slice(lastIndex));
    return html;
  }

  /** 判断一行是否含可被还原的脱敏标记（含则必须保留） */
  function containsMaskedToken(line, maskMapping) {
    if (!maskMapping) return false;
    for (const token of Object.keys(maskMapping)) {
      if (line.includes(token)) return true;
    }
    return false;
  }

  /**
   * 基于脱敏结果重建"占位符 → 明文"映射（后端未返回 maskMapping 时的兜底方案）。
   * 与前端 desensitizeDecoder.js 的算法保持一致：
   * 1. 从脱敏文本提取 [标记_序号]，按标记标识分组；
   * 2. 实体按类型分组，同类型内按 start 降序、按明文去重；
   * 3. 已知类型占位符按序号取对应实体明文；未知类型与剩余实体全局降序匹配。
   *
   * @param {string} desensitizedText 脱敏后的文本（含标记）
   * @param {Array<{type:string, originalText:string, start:number}>} entities 检测到的敏感实体
   * @returns {Record<string,string>} 占位符 → 明文
   */
  function buildMaskMapping(desensitizedText, entities) {
    const mapping = {};
    if (
      !desensitizedText ||
      !Array.isArray(entities) ||
      entities.length === 0
    ) {
      return mapping;
    }

    const tokens = [];
    let match;
    MASK_TOKEN_REGEX.lastIndex = 0;
    while ((match = MASK_TOKEN_REGEX.exec(desensitizedText)) !== null) {
      tokens.push({ token: match[0], label: match[1], seq: Number(match[2]) });
    }
    if (tokens.length === 0) {
      return mapping;
    }

    const sortedEntities = entities
      .filter((e) => e && e.originalText != null && typeof e.start === "number")
      .sort((a, b) => b.start - a.start);

    const entityByType = new Map();
    for (const e of sortedEntities) {
      if (!entityByType.has(e.type)) entityByType.set(e.type, []);
      const list = entityByType.get(e.type);
      if (!list.some((item) => item.originalText === e.originalText)) {
        list.push(e);
      }
    }

    const tokenByLabel = new Map();
    for (const t of tokens) {
      if (!tokenByLabel.has(t.label)) tokenByLabel.set(t.label, []);
      tokenByLabel.get(t.label).push(t);
    }

    const usedEntities = new Set();
    for (const [enumType, tokenName] of Object.entries(TYPE_TOKEN_NAME)) {
      const typeTokens = tokenByLabel.get(tokenName);
      if (!typeTokens || typeTokens.length === 0) continue;
      const typeEntities = entityByType.get(enumType);
      if (!typeEntities || typeEntities.length === 0) continue;
      typeTokens.sort((a, b) => a.seq - b.seq);
      for (const t of typeTokens) {
        const entity = typeEntities[t.seq - 1];
        if (entity) {
          mapping[t.token] = entity.originalText;
          usedEntities.add(entity);
        }
      }
    }

    const remainingTokens = [];
    for (const tokensOfLabel of tokenByLabel.values()) {
      for (const t of tokensOfLabel) {
        if (!mapping[t.token]) remainingTokens.push(t);
      }
    }
    if (remainingTokens.length > 0) {
      remainingTokens.sort((a, b) => a.seq - b.seq);
      const unusedEntities = sortedEntities.filter((e) => !usedEntities.has(e));
      for (const t of remainingTokens) {
        const entity = unusedEntities[t.seq - 1];
        if (entity) mapping[t.token] = entity.originalText;
      }
    }

    return mapping;
  }

  /** 从文本中提取所有脱敏标记（去重，保持出现顺序） */
  function extractMaskTokens(text) {
    const tokens = [];
    if (!text) return tokens;
    MASK_TOKEN_REGEX.lastIndex = 0;
    let m;
    while ((m = MASK_TOKEN_REGEX.exec(text)) !== null) {
      const token = m[0];
      if (!tokens.includes(token)) tokens.push(token);
    }
    return tokens;
  }

  function escapeHtml(str) {
    return String(str == null ? "" : str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  // ====== 行级噪声过滤（一键复原专用）======
  // 一键复原从页面提取文本时，可能混入侧边栏历史标题、页面功能按钮、系统提示、
  // 脱敏提示、时间标记等非对话内容。NOISE_LINE_PATTERNS 按"整行"精确匹配，
  // 仅当整行命中时剔除，避免误伤核心对话本身。
  // 注意：历史聊天标题内容不可穷举，主要依赖 content.js 的对话区定位将其排除，
  // 此处为兜底防线，仅覆盖可枚举的 UI 固定文案。
  const NOISE_LINE_PATTERNS = [
    // —— 系统提示 / 免责声明（主流 AI 站点通用）——
    /^本回答由\s*AI\s*生成/,
    /^以上内容由\s*AI\s*生成/,
    /^本回答为\s*AI\s*生成/,
    /内容仅供参考，请仔细甄别/,

    // —— 脱敏提示 / 复原状态（插件自身与配置页文案）——
    /^完整原始内容/,
    /^已还原\s*\d+\s*处脱敏标记/,
    /^\[\s*已还原[^\]]*\]/,

    // —— 页面功能按钮 / 操作项（整行）——
    /^复制原文$/,
    /^复制$/,
    /^关闭$/,
    /^开启新对话$/,
    /^置顶$/,
    /^一键复原$/,
    /^重新生成$/,
    /^停止生成$/,
    /^编辑$/,
    /^删除$/,
    /^分享$/,
    /^举报$/,
    /^更多$/,
    /^收起$/,
    /^展开$/,
    /^赞$/,
    /^踩$/,

    // —— DeepSeek 深度思考 / 联网搜索等模式残留 ——
    /^已深度思考/,
    /^深度思考$/,
    /^思考过程$/,
    /^展开思考过程$/,
    /^收起思考过程$/,
    /^联网搜索$/,
    /^已搜索到\s*\d+\s*个网页/,

    // —— 消息角色标题 / 平台名标签（整行）——
    /^(DeepSeek|ChatGPT|GPT-4o|Gemini|Kimi|豆包|通义千问|文心一言)$/,

    // —— 时间 / 日期标记（纯时间戳整行，避免误伤正文）——
    /^\d{1,2}:\d{2}(:\d{2})?$/,
    /^(昨天|今天|前天)\s*\d{1,2}:\d{2}$/,
    /^\d{4}[-/]\d{1,2}[-/]\d{1,2}\s*\d{1,2}:\d{2}$/,
    /^\d{1,2}月\d{1,2}日\s*\d{1,2}:\d{2}$/,

    // —— 纯符号 / 分隔装饰行 ——
    /^[-\u2014=*·•\u2013~\s>]+$/,
  ];

  /** 判断单行是否为噪声行（整行命中即视为噪声） */
  function isNoiseLine(line) {
    if (!line) return false;
    return NOISE_LINE_PATTERNS.some((re) => re.test(line));
  }

  /**
   * 行级噪声过滤：剔除从页面提取文本时混入的非对话内容行。
   *
   * 过滤规则（可叠加）：
   * 1. 空行 / 纯空白；
   * 2. NOISE_LINE_PATTERNS 整行命中的系统提示、功能按钮、时间标记、脱敏提示等；
   * 3. 连续重复行（部分站点会将同一提示在对话前后重复渲染）。
   *
   * 建议在解码后再调用（入参为已解码文本），避免明文被正则误判。
   *
   * @param {string} text 待过滤文本
   * @param {object} [options]
   * @param {boolean} [options.dedupe] 是否折叠连续重复行，默认 true
   * @returns {string} 过滤后的纯文本（保留原文换行）
   */
  function filterNoiseLines(text, options) {
    if (!text) return "";
    const opts = options || {};
    const dedupe = opts.dedupe !== false;
    const lines = String(text).split(/\r?\n/);
    const out = [];
    let prev = null;
    for (const raw of lines) {
      const line = raw.trim();
      if (!line) continue;
      if (isNoiseLine(line)) continue;
      if (dedupe && line === prev) continue;
      prev = line;
      out.push(line);
    }
    return out.join("\n");
  }

  return {
    decodeText,
    decodeWithHighlights,
    buildMaskMapping,
    extractMaskTokens,
    filterNoiseLines,
    isNoiseLine,
    escapeHtml,
    TYPE_TOKEN_NAME,
    NOISE_LINE_PATTERNS,
  };
});
