/**
 * 调用监控页 E2E 测试服务器
 *
 * 职责：
 *  1. 静态服务前端构建产物（front_end/dist-e2e），使监控页与接口同源（http://localhost:18080），
 *     与构建注入的 VITE_API_BASE_URL 一致，无需 CORS；
 *  2. 模拟 /gateway/v1/monitor/* 契约：概览 / 趋势 / 告警，
 *     内置"千级请求量"聚合数据（今日 1280 次、12 个标准平台、24 小时趋势），
 *     支持实时递增模拟（每次概览请求 +1，验证刷新机制）；
 *  3. 复刻后端权限白名单（X-Monitor-Role ∈ AUDITOR/ADMIN/OPERATOR，否则 403），
 *     验证监控页的权限适配。
 */
const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.MONITOR_PORT || 18080);
// 默认服务 E2E 专用构建产物（dist-e2e），避免污染正式构建 dist
const DIST = path.resolve(
  __dirname,
  process.env.MONITOR_DIST || "../../front_end/dist-e2e",
);

// ===== 千级请求量聚合数据 =====
const PROVIDERS = [
  { code: "OPENAI", name: "OpenAI (ChatGPT)", plugin: 210, api: 130 },
  { code: "DEEPSEEK", name: "DeepSeek", plugin: 190, api: 90 },
  { code: "QWEN", name: "通义千问", plugin: 120, api: 80 },
  { code: "KIMI", name: "Kimi", plugin: 90, api: 40 },
  { code: "DOUBAO", name: "豆包", plugin: 60, api: 30 },
  { code: "ERNIE", name: "文心一言", plugin: 50, api: 25 },
  { code: "HUNYUAN", name: "混元", plugin: 40, api: 20 },
  { code: "ANTHROPIC", name: "Anthropic (Claude)", plugin: 30, api: 15 },
  { code: "GEMINI", name: "Gemini", plugin: 20, api: 10 },
  { code: "PERPLEXITY", name: "Perplexity", plugin: 15, api: 8 },
  { code: "OLLAMA", name: "Ollama (本地)", plugin: 12, api: 5 },
  { code: "OTHER", name: "其他 / 未识别", plugin: 8, api: 2 },
];

const BASE_TOTAL = 1280; // 千级总量
let currentTotal = BASE_TOTAL;

const WHITELIST = ["AUDITOR", "ADMIN", "OPERATOR"];

function overviewBody() {
  const total = currentTotal;
  const byProvider = PROVIDERS.map((p, i) => {
    const count = p.plugin + p.api;
    return {
      code: p.code,
      name: p.name,
      count,
      pluginCount: p.plugin,
      apiCount: p.api,
      // 保留原有数据，仅记录动态总量
      order: i,
    };
  });
  // 按 count 降序
  byProvider.sort((a, b) => b.count - a.count);
  const totalPlugins = byProvider.reduce((s, p) => s + p.pluginCount, 0);
  const totalApis = byProvider.reduce((s, p) => s + p.apiCount, 0);
  return {
    date: new Date().toISOString().slice(0, 10),
    todayTotal: total,
    pluginTotal: totalPlugins,
    apiTotal: totalApis,
    byProvider,
    byChannel: [
      { channel: "BROWSER_PLUGIN", cnt: totalPlugins },
      { channel: "backend-api", cnt: totalApis },
    ],
    byRiskLevel: [
      { input_risk_level: "HIGH", cnt: 180 },
      { input_risk_level: "MEDIUM", cnt: 420 },
      { input_risk_level: "LOW", cnt: 580 },
      { input_risk_level: "NONE", cnt: 100 },
    ],
    byDecision: [
      { decision_action: "ALLOW", cnt: 960 },
      { decision_action: "DESENSITIZE_AND_ALLOW", cnt: 240 },
      { decision_action: "BLOCK", cnt: 80 },
    ],
    anomalyCount: 3,
  };
}

function trendBody(hours) {
  const h = Math.max(1, Math.min(48, Number(hours) || 24));
  const now = new Date();
  const points = [];
  for (let i = h - 1; i >= 0; i--) {
    const d = new Date(now.getTime() - i * 3600_000);
    const hourLabel = `${String(d.getHours()).padStart(2, "0")}:00`;
    points.push({
      hour: hourLabel,
      plugin: 20 + ((d.getHours() * 7) % 40),
      api: 10 + ((d.getHours() * 3) % 25),
      total: 30 + ((d.getHours() * 10) % 65),
    });
  }
  return { date: new Date().toISOString().slice(0, 10), hours: h, points };
}

function anomaliesBody() {
  return {
    date: new Date().toISOString().slice(0, 10),
    count: 3,
    items: [
      {
        id: "al-mock-001",
        level: "HIGH",
        type: "HIGH_FREQUENCY",
        title: "高频调用",
        detail:
          "员工账号 u-***（共 23 次）在 24 小时内高频调用外部模型，疑似脚本化访问。",
        count: 23,
        timeWindow: "24 小时",
        generatedAt: new Date().toISOString().slice(0, 19).replace("T", " "),
      },
      {
        id: "al-mock-002",
        level: "MEDIUM",
        type: "RISK_SPIKE",
        title: "高风险请求突增",
        detail:
          "当日高风险请求 12 次，超过阈值 10 次，需关注敏感信息外发风险。",
        count: 12,
        timeWindow: "当日",
        generatedAt: new Date().toISOString().slice(0, 19).replace("T", " "),
      },
      {
        id: "al-mock-003",
        level: "MEDIUM",
        type: "UNKNOWN_PROVIDER",
        title: "未登记平台调用",
        detail:
          "检测到 5 次调用未登记的外部模型平台（mystery-llm-xyz），请核实合规性。",
        count: 5,
        timeWindow: "当日",
        generatedAt: new Date().toISOString().slice(0, 19).replace("T", " "),
      },
    ],
  };
}

function isAllowed(req) {
  const role = (req.headers["x-monitor-role"] || "").trim().toUpperCase();
  return WHITELIST.includes(role);
}

function sendJson(res, status, obj) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(obj));
}

function serveStatic(res, pathname) {
  let rel = pathname === "/" ? "index.html" : pathname.slice(1);
  const file = path.join(DIST, rel);
  if (
    !file.startsWith(DIST) ||
    !fs.existsSync(file) ||
    fs.statSync(file).isDirectory()
  ) {
    res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    res.end("not found");
    return;
  }
  const ext = path.extname(file).toLowerCase();
  const types = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".txt": "text/plain; charset=utf-8",
  };
  res.writeHead(200, {
    "Content-Type": types[ext] || "application/octet-stream",
  });
  res.end(fs.readFileSync(file));
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);

  // 健康探活（Playwright webServer 等待此端点）
  if (req.method === "GET" && url.pathname === "/__monitor-health") {
    sendJson(res, 200, { ok: true });
    return;
  }

  // 监控接口：权限白名单校验（与后端 MonitorAuthInterceptor 一致）
  if (url.pathname.startsWith("/gateway/v1/monitor/")) {
    if (!isAllowed(req)) {
      sendJson(res, 403, { error: "forbidden: monitor role required" });
      return;
    }
    if (url.pathname.endsWith("/overview")) {
      const body = overviewBody();
      currentTotal += 1; // 模拟实时数据增长，供刷新机制断言
      sendJson(res, 200, body);
      return;
    }
    if (url.pathname.endsWith("/trend")) {
      sendJson(res, 200, trendBody(url.searchParams.get("hours")));
      return;
    }
    if (url.pathname.endsWith("/anomalies")) {
      sendJson(res, 200, anomaliesBody());
      return;
    }
    sendJson(res, 404, { error: "not found" });
    return;
  }

  // 测试辅助：重置/读取实时总量
  if (req.method === "POST" && url.pathname === "/__ctl/reset-total") {
    currentTotal = BASE_TOTAL;
    sendJson(res, 200, { ok: true, total: currentTotal });
    return;
  }

  // 静态页面（前端构建产物）
  serveStatic(res, url.pathname);
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`[monitor-server] serving dist on http://localhost:${PORT}`);
});
