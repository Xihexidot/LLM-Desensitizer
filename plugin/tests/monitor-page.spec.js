/**
 * 外部 LLM 调用监控页 E2E 测试（复用 Playwright，覆盖 Chromium + 本机 Edge 双浏览器 = 兼容性验证）
 *
 * 覆盖：
 *  1. 功能测试：页面完整渲染（指标卡 / 平台分布 / 图表 / 告警面板）；
 *  2. 权限测试：非白名单角色 403、无角色 403、合法角色放行（含前端"权限不足"提示分支）；
 *  3. 刷新机制：手动刷新与 10 秒自动刷新均能拉取最新数据；
 *  4. 压力测试：千级请求聚合数据下页面渲染时间与接口响应时间满足预算。
 */
const { test, expect } = require("@playwright/test");

const PAGE = "http://localhost:18080/";
const API = "http://localhost:18080/gateway/v1/monitor";

// 打开应用并通过侧边栏导航进入"调用监控"视图（前端无路由，视图切换由导航控制）
async function openMonitorPage(page) {
  await page.goto(PAGE, { waitUntil: "domcontentloaded" });
  await page.getByRole("link", { name: "调用监控" }).click();
}

test.describe("外部 LLM 调用监控页", () => {
  test("功能：核心数据与可视化组件完整渲染", async ({ page }) => {
    await openMonitorPage(page);

    // 页面标题
    await expect(page.getByText("外部 LLM 调用监控").first()).toBeVisible();

    // 指标卡
    for (const label of [
      "今日总请求",
      "浏览器插件",
      "网关 API",
      "调用平台数",
      "异常告警",
    ]) {
      await expect(page.getByText(label).first()).toBeVisible();
    }

    // 分平台统计：12 个标准平台卡片，含 OpenAI / DeepSeek / 通义千问 / 文心一言
    await expect(page.locator(".provider-card")).toHaveCount(12);
    for (const name of [
      "OpenAI (ChatGPT)",
      "DeepSeek",
      "通义千问",
      "文心一言",
    ]) {
      await expect(page.getByText(name).first()).toBeVisible();
    }

    // 图表（分布饼图 + 趋势柱状图）
    await expect(page.locator("canvas")).toHaveCount(2);

    // 告警面板
    await expect(page.getByText("异常风险告警").first()).toBeVisible();
    await expect(page.getByText("高频调用").first()).toBeVisible();
    await expect(page.getByText("高风险").first()).toBeVisible();
  });

  test("权限：白名单角色放行，非白名单与匿名返回 403", async ({ request }) => {
    // 合法角色 → 200
    for (const role of ["AUDITOR", "ADMIN", "OPERATOR"]) {
      const res = await request.get(`${API}/overview`, {
        headers: { "X-Monitor-Role": role },
      });
      expect(res.status(), `角色 ${role} 应放行`).toBe(200);
    }
    // 匿名 → 403
    const anon = await request.get(`${API}/overview`);
    expect(anon.status()).toBe(403);
    // 非白名单角色 → 403
    const user = await request.get(`${API}/overview`, {
      headers: { "X-Monitor-Role": "USER" },
    });
    expect(user.status()).toBe(403);
    // 小写角色归一 → 200
    const lower = await request.get(`${API}/overview`, {
      headers: { "X-Monitor-Role": "auditor" },
    });
    expect(lower.status()).toBe(200);
  });

  test("权限：后端 403 时页面展示权限不足提示", async ({ page }) => {
    // 拦截监控接口，模拟后端拒绝（权限校验失败分支）
    await page.route("**/gateway/v1/monitor/**", (route) =>
      route.fulfill({
        status: 403,
        contentType: "application/json",
        body: '{"error":"forbidden"}',
      }),
    );
    await openMonitorPage(page);
    await expect(page.getByText(/权限不足/).first()).toBeVisible();
  });

  test("刷新机制：手动刷新与 10 秒自动刷新均更新数据", async ({ page }) => {
    await page.request.post("http://localhost:18080/__ctl/reset-total");
    await openMonitorPage(page);

    const total = page.locator(".stat-card.primary .value");
    await expect(total).toHaveText(/128[0-9]/, { timeout: 5000 });

    // 手动刷新：mock 端实时总量 +1，显示值应变化
    const v1 = (await total.textContent()).trim();
    await page.getByRole("button", { name: "立即刷新" }).click();
    await expect(total).not.toHaveText(v1, { timeout: 5000 });

    // 自动刷新（10 秒定时器）：等待下一次轮询后数值继续变化
    const v2 = (await total.textContent()).trim();
    await expect(total).not.toHaveText(v2, { timeout: 15000 });
  });

  test("压力：千级请求聚合数据下页面加载与接口响应满足预算", async ({
    page,
  }) => {
    await page.request.post("http://localhost:18080/__ctl/reset-total");

    const t0 = Date.now();
    await page.goto(PAGE, { waitUntil: "load" });
    await page.getByRole("link", { name: "调用监控" }).click();
    await expect(page.getByText("今日总请求").first()).toBeVisible({
      timeout: 8000,
    });
    const renderMs = Date.now() - t0;

    // 指标卡展示千级总量
    const totalText = (
      await page.locator(".stat-card.primary .value").textContent()
    ).trim();
    expect(Number(totalText)).toBeGreaterThanOrEqual(1280);

    // 首屏渲染预算：5 秒内
    expect(renderMs, `首屏渲染耗时 ${renderMs}ms 超预算`).toBeLessThan(5000);

    // 千级数据下 24 小时趋势接口响应预算：1 秒内
    const apiMs = await page.evaluate(async () => {
      const t = performance.now();
      const res = await fetch(
        "http://localhost:18080/gateway/v1/monitor/trend?hours=24",
      );
      await res.json();
      return performance.now() - t;
    });
    expect(apiMs, `趋势接口耗时 ${apiMs.toFixed(1)}ms 超预算`).toBeLessThan(
      1000,
    );

    // 12 个平台卡全部渲染
    await expect(page.locator(".provider-card")).toHaveCount(12);
  });
});
