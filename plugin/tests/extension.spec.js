/**
 * AI 输入安全助手 —— 浏览器插件全流程 E2E 测试
 *
 * 覆盖：
 *  1. 功能链路：配置网关 → 页面输入敏感内容 → 点击发送 → 弹窗检测结果 → 发送脱敏内容 → 用户操作回传
 *  2. 安全场景：BLOCK 强制阻断（仅允许取消）；XSS 载荷不会在弹窗中执行
 *  3. 兼容性：同一套用例分别在 Chromium 与 Microsoft Edge（msedge channel）项目下执行
 */
const { test, expect, chromium } = require("@playwright/test");
const fs = require("fs");
const os = require("os");
const path = require("path");

const EXTENSION_PATH = path.resolve(__dirname, "..");
const MOCK_BASE = "http://127.0.0.1:8899";

/** 启动带插件的持久化浏览器上下文，返回 { context, extId } */
async function launchExtension(channel) {
  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "ai-guard-"));
  // channel：项目配置了 msedge 则用本机 Edge；chromium 项目显式用完整版 Chromium（新无头模式），
  // 避免默认 headless-shell 被第三方输入法钩子干扰（Windows 沙箱环境）
  const resolvedChannel = channel || "chromium";
  const context = await chromium.launchPersistentContext(userDataDir, {
    channel: resolvedChannel,
    headless: process.env.HEADED ? false : true,
    args: [
      `--disable-extensions-except=${EXTENSION_PATH}`,
      `--load-extension=${EXTENSION_PATH}`,
      "--disable-logging",
      "--disable-gpu",
    ],
  });

  // MV3 扩展以 Service Worker 方式运行，从中提取扩展 ID
  let sw = context.serviceWorkers()[0];
  if (!sw) {
    sw = await context.waitForEvent("serviceworker", { timeout: 20_000 });
  }
  const extId = new URL(sw.url()).host;
  return { context, extId };
}

/** 清空 mock 网关请求日志 */
async function resetMockLog() {
  await fetch(`${MOCK_BASE}/audit/reset`, { method: "POST" }).catch(() => {});
}

/** 读取 mock 网关请求日志 */
async function readMockLog() {
  const resp = await fetch(`${MOCK_BASE}/audit/requests`);
  return resp.json();
}

/** 在配置页完成网关地址与员工身份填写 */
async function configureGateway(context, extId, { gateway, userName, dept }) {
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extId}/config.html`);
  await page.fill("#gateway", gateway);
  if (userName) await page.fill("#userName", userName);
  if (dept) await page.fill("#dept", dept);
  await page.click("#btnSave");
  await expect(page.locator("#status")).toContainText("已保存", {
    timeout: 10_000,
  });
  await page.close();
}

test.describe("插件加载与基础能力", () => {
  test("扩展以 Service Worker 形式成功加载", async ({}, testInfo) => {
    const channel = testInfo.project.use.channel || undefined;
    const { context, extId } = await launchExtension(channel);
    try {
      expect(extId).toMatch(/^[a-p]{32}$/); // Chrome 扩展 ID 由 a-p 组成，共 32 位
      // 配置页可访问
      const page = await context.newPage();
      await page.goto(`chrome-extension://${extId}/config.html`);
      await expect(page.locator("#btnSave")).toBeVisible();
    } finally {
      await context.close();
    }
  });
});

test.describe("插件功能全流程", () => {
  test("敏感内容 → 网关检测 → 弹窗提示 → 发送脱敏内容", async ({}, testInfo) => {
    const channel = testInfo.project.use.channel || undefined;
    await resetMockLog();
    const { context, extId } = await launchExtension(channel);
    try {
      await configureGateway(context, extId, {
        gateway: "127.0.0.1:8899",
        userName: "测试员工",
        dept: "安全测试部",
      });

      const page = await context.newPage();
      await page.goto(MOCK_BASE);
      const sensitive =
        "我的手机号是 13812345678，身份证 330106198501011234，邮箱 zhangsan@example.com";
      await page.fill("#prompt", sensitive);
      await page.click("#send");

      // 弹窗出现：类型标签 + 风险徽标
      const overlay = page.locator(".ai-guard-overlay");
      await expect(overlay).toBeVisible({ timeout: 20_000 });
      await expect(overlay).toContainText("手机号");
      await expect(overlay).toContainText("身份证号");
      await expect(overlay).toContainText("邮箱");
      await expect(overlay.locator(".ai-guard-risk-badge")).toContainText(
        "中风险",
      );

      // 脱敏预览中不含明文
      const preview = await overlay
        .locator(".ai-guard-preview-box")
        .textContent();
      expect(preview).not.toContain("13812345678");
      expect(preview).not.toContain("330106198501011234");

      // 选择"发送脱敏内容"
      await overlay.locator("#aiGuardBtnSend").click();

      // 发送出去的必须是脱敏内容，原文不残留
      await expect(page.locator("#sent")).toContainText("[PHONE_NUMBER_", {
        timeout: 10_000,
      });
      const sent = await page.locator("#sent").textContent();
      expect(sent).toContain("[ID_CARD_");
      expect(sent).toContain("[EMAIL_");
      expect(sent).not.toContain("13812345678");
      expect(sent).not.toContain("zhangsan@example.com");

      // 网关侧：audit-check 收到原始内容 + 身份信息；confirm-action 回传用户选择
      const log = await readMockLog();
      const audit = log.find((r) => r.endpoint === "/plugin/audit-check");
      expect(audit).toBeTruthy();
      expect(audit.body.content).toContain("13812345678");
      expect(audit.body.userId).toBe("测试员工");
      expect(audit.body.department).toBe("安全测试部");
      expect(audit.body.targetProvider).toBeTruthy(); // 来自页面 hostname 的自动识别

      const confirm = log.find((r) => r.endpoint === "/plugin/confirm-action");
      expect(confirm).toBeTruthy();
      expect(confirm.body.userAction).toBe("DESENSITIZE_AND_SEND");
    } finally {
      await context.close();
    }
  });

  test("无敏感内容 → 不弹窗、原文直接放行", async ({}, testInfo) => {
    const channel = testInfo.project.use.channel || undefined;
    await resetMockLog();
    const { context, extId } = await launchExtension(channel);
    try {
      await configureGateway(context, extId, { gateway: "127.0.0.1:8899" });

      const page = await context.newPage();
      await page.goto(MOCK_BASE);
      const plain = "今天天气不错，帮我写一段关于杭州的介绍。";
      await page.fill("#prompt", plain);
      await page.click("#send");

      // 无弹窗，直接发送原文
      await expect(page.locator("#sent")).toContainText(plain, {
        timeout: 10_000,
      });
      await expect(page.locator(".ai-guard-overlay")).toHaveCount(0);
    } finally {
      await context.close();
    }
  });

  test("发送脱敏内容 → 一键复原 → 展示完整原始内容", async ({}, testInfo) => {
    const channel = testInfo.project.use.channel || undefined;
    await resetMockLog();
    const { context, extId } = await launchExtension(channel);
    try {
      await configureGateway(context, extId, {
        gateway: "127.0.0.1:8899",
        userName: "测试员工",
        dept: "安全测试部",
      });

      const page = await context.newPage();
      await page.goto(MOCK_BASE);
      const sensitive = "我的手机号是 13812345678，身份证 330106198501011234";
      await page.fill("#prompt", sensitive);
      await page.click("#send");

      // 弹窗出现，选择"发送脱敏内容"
      const overlay = page.locator(".ai-guard-overlay");
      await expect(overlay).toBeVisible({ timeout: 20_000 });
      await overlay.locator("#aiGuardBtnSend").click();

      // 发送出去的是脱敏内容，且 mock AI 将脱敏标记带回回复区
      await expect(page.locator("#sent")).toContainText("[PHONE_NUMBER_", {
        timeout: 10_000,
      });
      await expect(page.locator("#ai-reply")).toContainText(
        "[PHONE_NUMBER_1]",
        {
          timeout: 10_000,
        },
      );
      await expect(page.locator("#ai-reply")).toContainText("[ID_CARD_1]", {
        timeout: 10_000,
      });

      // 注入"一键复原"悬浮按钮
      const fab = page.locator("#ai-guard-restore-fab");
      await expect(fab).toBeVisible({ timeout: 10_000 });

      // 点击后复原面板展示完整原始内容
      await fab.click();
      const panel = page.locator("#ai-guard-restore-panel");
      await expect(panel).toBeVisible({ timeout: 10_000 });
      await expect(panel).toContainText("完整原始内容");
      await expect(panel).toContainText("13812345678");
      await expect(panel).toContainText("330106198501011234");
      await expect(panel).toContainText("已还原 4 处");
      // 原始数据必须全部还原，不留脱敏标记
      const bodyText = await panel.locator("#agrBody").textContent();
      expect(bodyText).not.toContain("[PHONE_NUMBER_");
      expect(bodyText).not.toContain("[ID_CARD_");

      // 复原结果仅保留核心对话：用户提问与 AI 回复均已还原为明文
      await expect(panel.locator("#agrBody")).toContainText(
        "我的手机号是 13812345678，身份证 330106198501011234",
      );
      await expect(panel.locator("#agrBody")).toContainText(
        "AI 回复：已收到您的信息（13812345678、330106198501011234），我们将为您妥善处理。",
      );

      // 侧边栏历史聊天标题 / 功能按钮 / 系统提示 / 时间标记 / 插件自身 UI 全部排除
      const NOISE_TEXTS = [
        "基于Web框架的具体业务API开发",
        "数据库速通",
        "囚徒健身新手计划",
        "课程大纲整理",
        "开启新对话",
        "置顶",
        "复制原文",
        "关闭",
        "一键复原",
        "完整原始内容",
        "本回答由 AI 生成",
        "内容仅供参考，请仔细甄别",
        "系统提示：当前对话已开启隐私保护",
        "2026-08-13 14:30",
        "昨天 21:15",
        "发送",
        // 历史多轮对话内容（仅保留最新一条对话，更早轮次必须被截断排除）
        "帮我写一份本周工作周报",
        "好的，为您整理周报模板",
        "帮我查一下银行卡",
        "当前余额为 8,520.00 元",
        "数据库速通怎么学最快",
        "SQL 增删改查",
        "2026-08-12 09:20",
        // DeepSeek 深度思考块 / 推理文本 / 平台名标签（思考残留必须整块剔除）
        "已深度思考",
        "判断属于建设银行借记卡",
        "好的，用户询问银行卡余额查询",
        "DeepSeek",
      ];
      for (const noise of NOISE_TEXTS) {
        await expect(panel.locator("#agrBody")).not.toContainText(noise, {
          timeout: 5_000,
        });
      }

      // 配置页剪贴板一键复原：映射在扩展会话内共享，可直接还原粘贴的脱敏文本
      const cfgPage = await context.newPage();
      await cfgPage.goto(`chrome-extension://${extId}/config.html`);
      await cfgPage.fill(
        "#restoreInput",
        "您的手机号是 [PHONE_NUMBER_1]，身份证是 [ID_CARD_1]",
      );
      await cfgPage.click("#btnRestore");
      await expect(cfgPage.locator("#restoreResult")).toContainText(
        "13812345678",
        { timeout: 10_000 },
      );
      await expect(cfgPage.locator("#restoreResult")).toContainText(
        "330106198501011234",
        { timeout: 10_000 },
      );
      await expect(cfgPage.locator("#restoreStatus")).toContainText(
        "已还原 2 处",
      );
    } finally {
      await context.close();
    }
  });

  test.describe("插件安全场景", () => {
    test("高危内容 → BLOCK 强制阻断，仅可取消、原文不得发送", async ({}, testInfo) => {
      const channel = testInfo.project.use.channel || undefined;
      await resetMockLog();
      const { context, extId } = await launchExtension(channel);
      try {
        await configureGateway(context, extId, { gateway: "127.0.0.1:8899" });

        const page = await context.newPage();
        await page.goto(MOCK_BASE);
        // 6 种不同类型敏感信息 → 命中强制阻断（超过 5 类）
        const attack =
          "手机 13812345678 身份证 330106198501011234 银行卡 6217001234567890 " +
          "邮箱 a@b.com 密码:abc12345 密钥 sk-abcdefghijklmnopqrstuvwxyz";
        await page.fill("#prompt", attack);
        await page.click("#send");

        const overlay = page.locator(".ai-guard-overlay");
        await expect(overlay).toBeVisible({ timeout: 20_000 });
        await expect(overlay.locator(".ai-guard-risk-badge")).toContainText(
          "高风险",
        );
        // 阻断场景：无"发送脱敏内容"与"发送原文"按钮，仅"确认取消"
        await expect(overlay.locator("#aiGuardBtnCancel")).toBeVisible();
        await expect(overlay.locator("#aiGuardBtnSend")).toHaveCount(0);
        await expect(overlay.locator("#aiGuardBtnOriginal")).toHaveCount(0);

        await overlay.locator("#aiGuardBtnCancel").click();

        // 原文不得被发送（#sent 为空，输入框内容未被清除）
        await expect(page.locator("#sent")).toHaveText("", { timeout: 5_000 });
        await expect(page.locator("#prompt")).toHaveValue(attack);

        // 用户取消操作已回传
        const log = await readMockLog();
        const confirm = log.find(
          (r) => r.endpoint === "/plugin/confirm-action",
        );
        expect(confirm).toBeTruthy();
        expect(confirm.body.userAction).toBe("CANCEL");
      } finally {
        await context.close();
      }
    });

    test("XSS 载荷注入 → 弹窗内 HTML 转义，脚本不执行", async ({}, testInfo) => {
      const channel = testInfo.project.use.channel || undefined;
      await resetMockLog();
      const { context, extId } = await launchExtension(channel);
      try {
        await configureGateway(context, extId, { gateway: "127.0.0.1:8899" });

        const page = await context.newPage();
        await page.goto(MOCK_BASE);
        const xssPayload = `<img src=x onerror="window.__xss=1"> 我的手机 13812345678`;
        await page.fill("#prompt", xssPayload);
        await page.click("#send");

        const overlay = page.locator(".ai-guard-overlay");
        await expect(overlay).toBeVisible({ timeout: 20_000 });

        // 原文以文本形式展示（HTML 实体已被转义，浏览器解析后是纯文本节点）
        await overlay.locator("#aiGuardToggle").click();
        await expect(overlay.locator("#aiGuardOriginal")).toContainText(
          "<img",
          {
            timeout: 5_000,
          },
        );
        // 弹窗内不得出现真实 img 元素，且脚本未执行
        await expect(overlay.locator("img")).toHaveCount(0);
        const xssFired = await page.evaluate(() => window.__xss === 1);
        expect(xssFired).toBe(false);

        // 关闭弹窗，不中断后续
        await overlay.locator("#aiGuardBtnCancel").click();
      } finally {
        await context.close();
      }
    });
  });
});
