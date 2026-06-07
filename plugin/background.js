const REVIEW_API_URL = "http://127.0.0.1:8080/plugin/audit-check";
const STORAGE_KEY_USER_ID = "ai-guard-user-id";
const STORAGE_KEY_DEPT = "ai-guard-dept";

chrome.runtime.onInstalled.addListener(() => {
  console.log("[AI 输入安全助手] 已安装，启用发送前输入检查");
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type !== "gateway-review-input") {
    return false;
  }

  reviewInput(message.payload)
    .then((result) => sendResponse({ ok: true, result }))
    .catch((error) =>
      sendResponse({
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      }),
    );

  return true;
});

async function reviewInput(payload) {
  const userId = await getUserId();
  const department = await getDept();

  const response = await fetch(REVIEW_API_URL, {
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
      targetProvider: payload?.targetProvider ?? '',
      strictMode: false,
      autoScenarioDetection: false,
    }),
  });

  if (!response.ok) {
    throw new Error(`网关检查失败，状态码: ${response.status}`);
  }

  return response.json();
}

// ========== 用户身份管理（Manifest V3 → chrome.storage.local）==========

async function getUserId() {
  try {
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
    const result = await chrome.storage.local.get(STORAGE_KEY_DEPT);
    return result[STORAGE_KEY_DEPT] || "";
  } catch {
    return "";
  }
}
