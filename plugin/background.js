// 你的本地网关地址
const GATEWAY_URL = "http://127.0.0.1:8080/api/v0/chat/completion";

// 设置动态规则，把 DeepSeek API 请求重定向到本地网关
chrome.declarativeNetRequest.updateDynamicRules({
  removeRuleIds: [1], // 先清除旧规则
  addRules: [
    {
      id: 1,
      priority: 1,
      action: {
        type: "redirect",
        redirect: {
          url: GATEWAY_URL,
        },
      },
      condition: {
        urlFilter: "chat.deepseek.com/api/v0/chat/completion",
        resourceTypes: ["xmlhttprequest"],
      },
    },
  ],
});

console.log("[安全网关] 规则已加载，拦截 DeepSeek API 请求并重定向到本地网关");
