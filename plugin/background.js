const DEFAULT_GATEWAY = "http://127.0.0.1:8080";
const STORAGE_KEY_GATEWAY = "ai-guard-gateway";
const STORAGE_KEY_USER_ID = "ai-guard-user-id";
const STORAGE_KEY_USER_NAME = "ai-guard-user-name";
const STORAGE_KEY_DEPT = "ai-guard-dept";

importScripts("managed-store.js");

// 加上 [AI-Guard BG] 前缀
function log(...args) {
  console.log("[AI-Guard BG]", ...args);
}

chrome.runtime.onInstalled.addListener(() => {
  log("extension installed/updated");
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  log(
    "onMessage received type=",
    message?.type,
    "from tabId=",
    sender?.tab?.id,
  );
  if (message?.type === "gateway-review-input") {
    reviewInput(message.payload)
      .then((result) => {
        log(
          "gateway-review-input success, auditEventId=",
          result?.auditEventId,
          "riskLevel=",
          result?.riskLevel,
        );
        sendResponse({ ok: true, result });
      })
      .catch((error) => {
        const errMsg = error instanceof Error ? error.message : String(error);
        log("gateway-review-input failed:", errMsg, error?.stack);
        sendResponse({ ok: false, error: errMsg });
      });
    return true;
  }
  if (message?.type === "check-gateway-status") {
    (async () => {
      const configured = await hasConfiguredGateway();
      log("check-gateway-status responded configured=", configured);
      sendResponse({ configured });
    })();
    return true;
  }
  if (message?.type === "open-config") {
    chrome.action.openPopup().catch((e) => {
      log("openPopup failed:", e?.message, "→ fallback to chrome.tabs.create");
      chrome.tabs.create({ url: chrome.runtime.getURL("config.html") });
    });
    sendResponse({ ok: true });
    return false;
  }
  log("onMessage: unhandled message type=", message?.type);
  return false;
});

/**
 * 是否已显式配置网关地址（用户填了，不是默认值）。
 * content.js 据此判断走纯前端脱敏还是后端深度检测。
 */
async function hasConfiguredGateway() {
  try {
    const result = await chrome.storage.local.get(STORAGE_KEY_GATEWAY);
    const raw = result[STORAGE_KEY_GATEWAY];
    const configured = !!(raw && raw.trim().length > 0);
    log(
      "hasConfiguredGateway: raw=",
      raw ? `"${raw.trim().slice(0, 40)}"` : "(empty)",
      "→ configured=",
      configured,
    );
    return configured;
  } catch (e) {
    log("hasConfiguredGateway: storage.local.get failed:", e?.message);
    return false;
  }
}

async function getBaseUrl() {
  const result = await chrome.storage.local.get(STORAGE_KEY_GATEWAY);
  let raw = result[STORAGE_KEY_GATEWAY];
  if (!raw) {
    log(
      "getBaseUrl: no gateway configured, using DEFAULT_GATEWAY=",
      DEFAULT_GATEWAY,
    );
    return DEFAULT_GATEWAY;
  }
  if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
    raw = "http://" + raw;
  }
  log("getBaseUrl: resolved baseUrl=", raw);
  return raw;
}

async function reviewInput(payload) {
  const userId = await getUserId();
  const department = await getDept();
  const managedCtx = await getManagedContext();
  const baseUrl = await getBaseUrl();
  const requestBody = {
    content: payload?.content ?? "",
    dataType: "TEXT",
    language: payload?.language ?? "zh",
    userId: payload?.userId ?? userId,
    department: payload?.department ?? department,
    companyName: managedCtx.companyName ?? "",
    complianceMode: managedCtx.complianceMode ?? "RELAXED",
    targetProvider: payload?.targetProvider ?? "",
    strictMode: false,
    autoScenarioDetection: false,
  };

  log(
    "reviewInput: POST",
    baseUrl + "/plugin/audit-check",
    "userId=",
    requestBody.userId,
    "company=",
    requestBody.companyName,
    "mode=",
    requestBody.complianceMode,
    "targetProvider=",
    requestBody.targetProvider,
    "contentLen=",
    requestBody.content.length,
  );

  let response;
  try {
    response = await fetch(`${baseUrl}/plugin/audit-check`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify(requestBody),
    });
  } catch (networkErr) {
    log(
      "reviewInput: network error (backend unreachable?):",
      networkErr?.message,
    );
    throw networkErr;
  }

  log("reviewInput: response status=", response.status, response.statusText);
  if (!response.ok) {
    const bodyText = await response.text().catch(() => "");
    log("reviewInput: non-OK response body:", bodyText.slice(0, 200));
    throw new Error(
      `网关检查失败，状态码: ${response.status}，响应: ${bodyText.slice(0, 100)}`,
    );
  }

  const json = await response.json();
  log(
    "reviewInput: parsed response riskLevel=",
    json?.riskLevel,
    "entities=",
    json?.detectedEntities?.length ?? 0,
  );
  return json;
}

// ========== 用户身份管理（Manifest V3 → chrome.storage）==========
// 优先级：企业 MDM/Group Policy 推送(chrome.storage.managed，只读) > 员工手动填写 > 自动生成 ID
// 企业推送的键名必须与 managed_schema.json 保持一致（userId/department/companyName/gatewayUrl/complianceMode）

async function getManagedContext() {
  try {
    const { source, profile } = await ManagedStore.getManagedProfile();
    log(
      "getManagedContext: source=",
      source,
      "keys=",
      profile ? Object.keys(profile) : "none",
    );
    return profile || {};
  } catch {
    return {};
  }
}

async function getUserId() {
  try {
    // 1) 企业 MDM/GPO 推送（只读，用户改不了）
    const managed = await getManagedContext();
    if (managed.userId) return managed.userId;

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
    const managed = await getManagedContext();
    if (managed.department) return managed.department;

    // 2) 员工手动填写
    const result = await chrome.storage.local.get(STORAGE_KEY_DEPT);
    return result[STORAGE_KEY_DEPT] || "";
  } catch {
    return "";
  }
}
