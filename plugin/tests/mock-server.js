/**
 * Mock 安全网关（浏览器插件 E2E 测试用）
 *
 * 职责：
 *  1. 静态服务模拟 AI 对话页面（GET /）；
 *  2. 实现插件调用的后端契约 /plugin/audit-check 与 /plugin/confirm-action，
 *     内置轻量规则检测（手机号/身份证/银行卡/邮箱/密码/API Key），返回与真实后端一致的响应结构；
 *  3. 记录所有请求（GET /audit/requests，POST /audit/reset 清空），供 Playwright 断言。
 */
const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.MOCK_PORT || 8899);
const MOCK_PAGE = path.join(__dirname, "mock-ai-page.html");

/** 已收到的插件请求日志 */
const requests = [];

// ===== 轻量敏感信息检测（与真实后端响应结构对齐） =====
const RULES = [
  // 手机号需带数字边界，避免与身份证号内部 11 位数字段误匹配（与真实后端 (?<!\d)...(?!\d) 行为一致）
  { type: "PHONE_NUMBER", re: /(?<!\d)1[3-9]\d{9}(?!\d)/g },
  { type: "ID_CARD", re: /(?<!\d)\d{17}[\dXx](?!\d)/g },
  { type: "BANK_CARD", re: /(?<!\d)[1-9]\d{15,18}(?!\d)/g },
  { type: "EMAIL", re: /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/g },
  { type: "PASSWORD", re: /密码[:：]\s*(\S+)/g },
  { type: "API_KEY", re: /\bsk-[A-Za-z0-9_-]{10,}\b/g },
];

function detectEntities(content) {
  const entities = [];
  for (const rule of RULES) {
    rule.re.lastIndex = 0;
    let m;
    while ((m = rule.re.exec(content))) {
      const text = m[1] || m[0];
      if (text) entities.push({ type: rule.type, originalText: text });
    }
  }
  return entities;
}

/**
 * 按规则逐条替换原文中的敏感 token 为 [TYPE_n] 占位符。
 * 先按原始匹配顺序收集全部 token，再逐次替换首个剩余出现，
 * 同一 token 多次出现时编号递增（第二次出现 → [TYPE_2]）。
 */
function maskText(content) {
  let out = content;
  for (const rule of RULES) {
    const tokens = [];
    rule.re.lastIndex = 0;
    let m;
    while ((m = rule.re.exec(out))) {
      const token = m[1] || m[0];
      if (token) tokens.push(token);
    }
    const counts = {};
    for (const token of tokens) {
      counts[token] = (counts[token] || 0) + 1;
      out = out.replace(token, `[${rule.type}_${counts[token]}]`);
    }
  }
  return out;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
}

function sendJson(res, status, obj) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(obj));
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);

  // 模拟 AI 对话页面
  if (req.method === "GET" && url.pathname === "/") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(fs.readFileSync(MOCK_PAGE));
    return;
  }

  // 请求日志查询 / 清空（测试辅助接口）
  if (req.method === "GET" && url.pathname === "/audit/requests") {
    sendJson(res, 200, requests);
    return;
  }
  if (req.method === "POST" && url.pathname === "/audit/reset") {
    requests.length = 0;
    sendJson(res, 200, { ok: true });
    return;
  }

  // 插件核心契约：内容审计
  if (req.method === "POST" && url.pathname === "/plugin/audit-check") {
    const body = await readBody(req);
    const payload = JSON.parse(body);
    const content = payload.content || "";
    const entities = detectEntities(content);
    const desensitized = maskText(content);
    const uniqueTypes = [...new Set(entities.map((e) => e.type))];

    // 与真实后端一致的决策规则：无敏感→放行；超过 5 类→强制阻断；否则脱敏后放行
    let riskLevel = "NONE";
    let decisionAction = "ALLOW";
    if (uniqueTypes.length > 5) {
      riskLevel = "HIGH";
      decisionAction = "BLOCK";
    } else if (uniqueTypes.length > 0) {
      riskLevel = "MEDIUM";
      decisionAction = "DESENSITIZE_AND_ALLOW";
    }

    const record = {
      endpoint: "/plugin/audit-check",
      body: payload,
      time: Date.now(),
    };
    requests.push(record);

    sendJson(res, 200, {
      detectedEntities: entities,
      desensitizedContent: desensitized,
      auditEventId: "evt-mock-" + requests.length,
      riskLevel,
      decisionAction,
    });
    return;
  }

  // 插件用户操作确认
  if (req.method === "POST" && url.pathname === "/plugin/confirm-action") {
    const body = await readBody(req);
    requests.push({
      endpoint: "/plugin/confirm-action",
      body: JSON.parse(body),
      time: Date.now(),
    });
    sendJson(res, 200, {});
    return;
  }

  sendJson(res, 404, { error: "not found" });
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`[mock-gateway] listening on http://127.0.0.1:${PORT}`);
});
