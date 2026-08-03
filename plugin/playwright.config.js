/**
 * 浏览器插件自动化测试 - Playwright 配置
 *
 * 说明：
 *  - 通过 webServer 自动拉起 Mock 安全网关（tests/mock-server.js），模拟后端 /plugin/audit-check 契约；
 *  - projects：chromium（内置 Chromium） + msedge（本机已安装 Microsoft Edge 时自动启用），
 *    实现"主流浏览器版本适配验证"；
 *  - 插件以 unpacked 方式加载（--load-extension），MV3 Service Worker 由 Playwright 自动管理。
 *
 * 使用：
 *  - npm install            # 安装依赖（首次需执行 npx playwright install chromium）
 *  - npm test               # 全量（Chromium + 本机 Edge）
 *  - npm run test:headed    # 有头模式（便于观察插件弹窗）
 */
const { defineConfig, devices } = require("@playwright/test");
const fs = require("fs");

const MOCK_PORT = 8899;

// 常见 Microsoft Edge 安装路径（Windows）
const EDGE_PATHS = [
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
  "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
];
const edgeInstalled = () => EDGE_PATHS.some((p) => fs.existsSync(p));

const projects = [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }];
if (edgeInstalled()) {
  projects.push({
    name: "msedge",
    use: { ...devices["Desktop Edge"], channel: "msedge" },
  });
}

module.exports = defineConfig({
  testDir: "./tests",
  timeout: 90_000,
  expect: { timeout: 15_000 },
  // 插件用例各自使用独立浏览器上下文，串行执行避免相互干扰
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [
    ["list"],
    ["html", { open: "never", outputFolder: "playwright-report" }],
    ["json", { outputFile: "playwright-report/results.json" }],
  ],
  use: {
    baseURL: `http://127.0.0.1:${MOCK_PORT}`,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects,
  webServer: [
    {
      command: "node tests/mock-server.js",
      url: `http://127.0.0.1:${MOCK_PORT}/`,
      reuseExistingServer: true,
      timeout: 30_000,
    },
    {
      // 监控页 E2E：静态服务 front_end/dist-e2e + 模拟 /gateway/v1/monitor/*（千级聚合数据 + 403 权限）
      // 前置要求：先构建 E2E 产物（front_end/ 下：$env:VITE_API_BASE_URL="http://localhost:18080"; npm run build -- --outDir dist-e2e）
      command: "node tests/monitor-server.js",
      url: "http://localhost:18080/__monitor-health",
      reuseExistingServer: false,
      timeout: 30_000,
    },
  ],
});
