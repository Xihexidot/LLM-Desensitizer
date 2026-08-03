/**
 * managed-store.js 单元测试（企业策略存储模块）
 *
 * 使用 Node 内置测试框架运行：node --test tests/managed-store.unit.js
 * 通过 vm 沙箱注入伪 chrome.storage.managed 环境，验证：
 *  1. 企业策略推送后能正确读取（source=managed）
 *  2. 未推送策略 / 键名不匹配 → source=none（降级到员工手动配置）
 *  3. 非 Chromium 环境（无 chrome.storage.managed）→ 返回 null，不抛异常
 */
const { test } = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const SOURCE_FILE = path.join(__dirname, "..", "managed-store.js");

/** 在 vm 沙箱中加载 managed-store.js，注入伪 chrome，返回 ManagedStore 模块 */
function loadManagedStore(chrome) {
  const code = fs.readFileSync(SOURCE_FILE, "utf8");
  const sandbox = { console, chrome };
  sandbox.self = sandbox; // IIFE 以 self 作为全局对象挂载模块
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox, { filename: "managed-store.js" });
  return sandbox.ManagedStore;
}

test("企业策略已推送：getManagedProfile 返回 managed 画像", async () => {
  const chrome = {
    storage: {
      managed: {
        async get() {
          return {
            userId: "E10086",
            department: "安全部",
            companyName: "示例集团",
            gatewayUrl: "https://gw.example.com",
            complianceMode: "STRICT",
          };
        },
      },
    },
  };
  const store = loadManagedStore(chrome);
  const result = await store.getManagedProfile();
  assert.strictEqual(result.source, "managed");
  assert.strictEqual(result.profile.userId, "E10086");
  assert.strictEqual(result.profile.department, "安全部");
  assert.strictEqual(result.profile.complianceMode, "STRICT");
});

test("未推送策略：getManagedProfile 返回 none，不抛异常", async () => {
  const chrome = {
    storage: {
      managed: {
        async get() {
          return {};
        },
      },
    },
  };
  const store = loadManagedStore(chrome);
  const result = await store.getManagedProfile();
  assert.strictEqual(result.source, "none");
  assert.strictEqual(result.profile, null);
});

test("策略键名与 schema 不一致：视为未推送（防错误注入）", async () => {
  const chrome = {
    storage: {
      managed: {
        async get() {
          return { evilKey: "not-a-valid-policy-key", another: "x" };
        },
      },
    },
  };
  const store = loadManagedStore(chrome);
  const result = await store.getManagedProfile();
  assert.strictEqual(result.source, "none");
  assert.strictEqual(result.profile, null);
});

test("部分键有值：仅返回有效键（空串/空白被剔除）", async () => {
  const chrome = {
    storage: {
      managed: {
        async get() {
          return { userId: "E2024", department: "   ", companyName: "" };
        },
      },
    },
  };
  const store = loadManagedStore(chrome);
  const result = await store.getManagedProfile();
  assert.strictEqual(result.source, "managed");
  assert.deepStrictEqual(Object.keys(result.profile), ["userId"]);
  assert.strictEqual(result.profile.userId, "E2024");
});

test("非 Chromium 环境（无 chrome.storage.managed）：返回 none，容错不崩溃", async () => {
  const chrome = { storage: { local: {} } };
  const store = loadManagedStore(chrome);
  const result = await store.getManagedProfile();
  assert.strictEqual(result.source, "none");
  assert.strictEqual(result.profile, null);
});

test("managed 区域抛异常：捕获并降级为 none", async () => {
  const chrome = {
    storage: {
      managed: {
        async get() {
          throw new Error("permission denied");
        },
      },
    },
  };
  const store = loadManagedStore(chrome);
  const result = await store.getManagedProfile();
  assert.strictEqual(result.source, "none");
});
