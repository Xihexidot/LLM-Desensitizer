const REVIEW_API_URL = "http://127.0.0.1:8080/desensitize/text";

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
      strictMode: false,
      autoScenarioDetection: false,
    }),
  });

  if (!response.ok) {
    throw new Error(`网关检查失败，状态码: ${response.status}`);
  }

  return response.json();
}
