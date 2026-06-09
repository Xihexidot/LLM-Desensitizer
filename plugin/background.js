const DEFAULT_GATEWAY = "http://127.0.0.1:8080";
const STORAGE_KEY_GATEWAY = "ai-guard-gateway";
const STORAGE_KEY_USER_ID = "ai-guard-user-id";
const STORAGE_KEY_USER_NAME = "ai-guard-user-name";
const STORAGE_KEY_DEPT = "ai-guard-dept";

chrome.runtime.onInstalled.addListener(() => {
  console.log("[AI 输入安全助手] 已安装");
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "gateway-review-input") {
    reviewInput(message.payload)
      .then((result) => sendResponse({ ok: true, result }))
      .catch((error) =>
        sendResponse({
          ok: false,
          error: error instanceof Error ? error.message : String(error),
        }),
      );
    return true;
  }
  return false;
});

async function getBaseUrl() {
  const result = await chrome.storage.local.get(STORAGE_KEY_GATEWAY);
  let raw = result[STORAGE_KEY_GATEWAY];
  if (!raw) return DEFAULT_GATEWAY;
  if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
    raw = "http://" + raw;
  }
  return raw;
}

async function reviewInput(payload) {
  const userId = await getUserId();
  const department = await getDept();
  const baseUrl = await getBaseUrl();

  const response = await fetch(`${baseUrl}/plugin/audit-check`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify({
      content: payload?.content ?? "",
      dataType: "TEXT",
      language: payload?.language ?? "zh",
      userId: payload?.userId ?? userId,
      department: payload?.department ?? department,
      targetProvider: payload?.targetProvider ?? "",
      strictMode: false,
      autoScenarioDetection: false,
    }),
  });

  if (!response.ok) {
    throw new Error(`网关检查失败，状态码: ${response.status}`);
  }

  return response.json();
}

// ========== 用户身份管理（Manifest V3 → chrome.storage）==========
// 优先级：企业 MDM/Group Policy 推送 > 员工手动填写 > 自动生成 ID
// chrome.storage.managed 由 IT 管理员通过 Windows GPO / Mac MDM / Linux policies 推送，只读，用户无权修改。

async function getUserId() {
  try {
    // 1) 企业 MDM 推送（只读，用户改不了）
    try {
      const managed = await chrome.storage.managed.get("userId");
      if (managed.userId) return managed.userId;
    } catch (_) {
      /* managed storage 在非企业环境不可用 */
    }

    // 2) 员工在配置面板填写的姓名/工号
    const nameResult = await chrome.storage.local.get(STORAGE_KEY_USER_NAME);
    if (nameResult[STORAGE_KEY_USER_NAME])
      return nameResult[STORAGE_KEY_USER_NAME];

    // 3) 首次使用时自动生成的随机 ID
    const result = await chrome.storage.local.get(STORAGE_KEY_USER_ID);
    if (result[STORAGE_KEY_USER_ID]) return result[STORAGE_KEY_USER_ID];
    const id = "user-" + Date.now().toString(36);
    await chrome.storage.local.set({ [STORAGE_KEY_USER_ID]: id });
    return id;
  } catch {
    return "unknown";
  }
}

async function getDept() {
  try {
    // 1) 企业 MDM 推送
    try {
      const managed = await chrome.storage.managed.get("department");
      if (managed.department) return managed.department;
    } catch (_) {
      /* 非企业环境 */
    }

    // 2) 员工手动填写
    const result = await chrome.storage.local.get(STORAGE_KEY_DEPT);
    return result[STORAGE_KEY_DEPT] || "";
  } catch {
    return "";
  }
}
