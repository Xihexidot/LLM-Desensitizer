const REVIEW_API_URL = "http://127.0.0.1:8080/plugin/audit-check";
const CONFIRM_API_URL = "http://127.0.0.1:8080/plugin/confirm-action";

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
      userId: payload?.userId ?? getUserId(),
      department: payload?.department ?? getDept(),
      strictMode: false,
      autoScenarioDetection: false,
    }),
  });

  if (!response.ok) {
    throw new Error(`网关检查失败，状态码: ${response.status}`);
  }

  return response.json();
}

// ========== 用户身份管理 ==========

function getUserId() {
  try {
    const val = localStorage.getItem("ai-guard-user-id");
    if (val) return val;
    const id = "user-" + Date.now().toString(36);
    localStorage.setItem("ai-guard-user-id", id);
    return id;
  } catch {
    return "unknown";
  }
}

function getDept() {
  try {
    const val = localStorage.getItem("ai-guard-dept");
    return val || "";
  } catch {
    return "";
  }
}

// 后台通过 runtime message 支持 { type: "confirm-action" } 调用
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type !== "confirm-action") {
    return false;
  }

  fetch(CONFIRM_API_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      auditEventId: message.auditEventId,
      userAction: message.userAction,
    }),
  })
    .then(() => sendResponse({ ok: true }))
    .catch((error) => sendResponse({ ok: false, error: error.message }));

  return true;
});
