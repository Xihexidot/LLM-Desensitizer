(function () {
  "use strict";

  const bypassElements = new WeakMap();
  let gatewayConfiguredCache = null;

  // ====== 调试开关：设为 false 关闭日志 ======
  const DEBUG = true;
  function log(...args) {
    if (DEBUG) console.log("[AI-Guard]", ...args);
  }

  document.addEventListener("click", handleClick, true);
  document.addEventListener("keydown", handleKeydown, true);
  log("content script loaded");

  function detectCurrentProvider() {
    try {
      const host = window.location.hostname || "";
      if (host.includes("deepseek")) return "DeepSeek";
      if (host.includes("chatgpt") || host.includes("openai")) return "ChatGPT";
      if (host.includes("kimi") || host.includes("moonshot")) return "Kimi";
      if (host.includes("tongyi") || host.includes("qwen")) return "通义千问";
      if (host.includes("doubao") || host.includes("volces")) return "豆包";
      if (host.includes("claude") || host.includes("anthropic"))
        return "Claude";
      if (host.includes("gemini") || host.includes("google")) return "Gemini";
      if (host.includes("wenxin") || host.includes("baidu")) return "文心一言";
      if (host.includes("hunyuan")) return "混元";
      if (host.includes("perplexity")) return "Perplexity";
      return host || "未知平台";
    } catch {
      return "未知平台";
    }
  }

  // ====== 核心：在任何 textarea/contenteditable 同一容器内查找可能的发送按钮 ======
  function findSendButtonNearInput(input) {
    if (!input) return null;
    let container = input.parentElement;
    for (let i = 0; i < 8 && container; i++) {
      const btns = container.querySelectorAll(
        'button, [role="button"], div[class*="send"], div[class*="submit"], ' +
          'svg[class*="send"], svg[class*="submit"], path[class*="send"]',
      );
      if (btns.length > 0) return btns[0];
      container = container.parentElement;
    }
    return null;
  }

  // 走到点击事件的按钮（包括其祖先 button）
  function resolveClickedButton(target) {
    if (!target) return null;
    // 向上遍历直到找到一个 button 或 role=button
    let el = target;
    while (el && el !== document.body) {
      const tag = (el.tagName || "").toLowerCase();
      if (tag === "button") return el;
      if (el.getAttribute && el.getAttribute("role") === "button") return el;
      // div/span/svg 配合 clickable 样式也可能充当按钮
      if ((tag === "div" || tag === "span" || tag === "svg") && el.onclick)
        return el;
      el = el.parentElement;
    }
    return null;
  }

  function handleClick(event) {
    // 是否点击了一个靠近输入框的可点击元素？
    const clickedBtn = resolveClickedButton(event.target);
    if (!clickedBtn || shouldBypass(clickedBtn)) return;

    // 找到页面中的输入框
    const input = findMainInput();
    if (!input || shouldBypass(input)) return;

    // 判断这个按钮是否为输入框的关联发送按钮
    const sendBtn = findSendButtonNearInput(input);
    if (!sendBtn) return;

    // clickedBtn 必须是 sendBtn 或 sendBtn 的子元素
    if (clickedBtn !== sendBtn && !sendBtn.contains(clickedBtn)) return;

    const content = getEditableText(input);
    if (!content) return;

    log("click intercepted", content.substring(0, 50));
    event.preventDefault();
    event.stopImmediatePropagation();
    reviewAndContinue({ input, trigger: clickedBtn, content });
  }

  function handleKeydown(event) {
    if (event.key !== "Enter" || event.shiftKey || event.isComposing) return;

    const input = findMainInput();
    if (!input || shouldBypass(input)) return;

    // 确认焦点或在输入框内部
    if (
      !input.contains(document.activeElement) &&
      document.activeElement !== input
    )
      return;

    const content = getEditableText(input);
    if (!content) return;

    log("enter key intercepted", content.substring(0, 50));
    event.preventDefault();
    event.stopImmediatePropagation();
    reviewAndContinue({ input, trigger: null, content });
  }

  // ====== 查找页面主输入框 ======
  function findMainInput() {
    // 优先找已聚焦的
    const focused = document.activeElement;
    if (focused) {
      const ed = findEditable(focused);
      if (ed) return ed;
    }
    // 找页面中最大的 textarea
    const textareas = document.querySelectorAll("textarea");
    let best = null,
      bestArea = 0;
    for (const ta of textareas) {
      const area = ta.offsetWidth * ta.offsetHeight;
      if (area > bestArea) {
        best = ta;
        bestArea = area;
      }
    }
    if (best) return best;
    // 找 contenteditable
    return document.querySelector('[contenteditable="true"]');
  }

  // ====== 脱敏后继续发送 ======
  async function reviewAndContinue({ input, trigger, content }) {
    try {
      if (looksAlreadyDesensitized(content)) {
        continueSend({ input, trigger, content });
        return;
      }
      if (!isChromeRuntimeAvailable()) {
        window.alert(
          "[AI 输入安全助手] 插件上下文已失效，请刷新当前页面后重试。",
        );
        return;
      }

      const hasGateway = await checkGatewayConfigured();
      if (!hasGateway) {
        const goConfig = window.confirm(
          "[AI 输入安全助手] 尚未配置安全网关地址，无法进行敏感信息检测。\n\n点击【确定】前往配置页面，点击【取消】本次继续发送原文。",
        );
        if (goConfig) {
          try {
            await chrome.runtime.sendMessage({ type: "open-config" });
          } catch {
            window.open(chrome.runtime.getURL("config.html"));
          }
          showActionToast("CONFIG_NEEDED");
        } else {
          continueSend({ input, trigger, content });
        }
        return;
      }

      const response = await chrome.runtime.sendMessage({
        type: "gateway-review-input",
        payload: {
          content,
          language: guessLanguage(content),
          targetProvider: detectCurrentProvider(),
        },
      });

      if (!response?.ok) {
        const allow = window.confirm(
          "[AI 输入安全助手] 安全网关无响应。\n\n请确认网关地址正确且后端服务已启动。\n点击【确定】继续原文发送，点击【取消】终止发送。",
        );
        if (allow) continueSend({ input, trigger, content });
        return;
      }

      const result = response.result;
      const auditEventId = result?.auditEventId;
      const detectedEntities = Array.isArray(result?.detectedEntities)
        ? result.detectedEntities
        : [];
      const desensitizedContent = result?.desensitizedContent || content;
      const riskLevel = result?.riskLevel || "NONE";
      const decisionAction = result?.decisionAction || "ALLOW";

      if (!detectedEntities.length || riskLevel === "NONE") {
        continueSend({ input, trigger, content });
        return;
      }

      const choice = await Popup.show({
        detectedEntities,
        desensitizedContent,
        originalContent: content,
        riskLevel,
        decisionAction,
        source: "gateway",
      });

      if (choice === "send") {
        notifyConfirmAction(auditEventId, "DESENSITIZE_AND_SEND");
        continueSend({ input, trigger, content: desensitizedContent });
      } else if (choice === "send-original") {
        notifyConfirmAction(auditEventId, "SEND_ORIGINAL");
        continueSend({ input, trigger, content });
      } else {
        notifyConfirmAction(auditEventId, "CANCEL");
      }
    } catch (error) {
      log("review failed", error);
    }
  }

  async function checkGatewayConfigured() {
    if (gatewayConfiguredCache !== null) return gatewayConfiguredCache;
    try {
      const resp = await chrome.runtime.sendMessage({
        type: "check-gateway-status",
      });
      gatewayConfiguredCache = resp?.configured || false;
    } catch {
      gatewayConfiguredCache = false;
    }
    return gatewayConfiguredCache;
  }

  function notifyConfirmAction(auditEventId, userAction) {
    if (!auditEventId) return;
    getBaseUrl()
      .then((baseUrl) => {
        fetch(baseUrl + "/plugin/confirm-action", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ auditEventId, userAction }),
        }).catch(() => {});
      })
      .catch(() => {});
    showActionToast(userAction);
  }

  async function getBaseUrl() {
    try {
      const result = await chrome.storage.local.get("ai-guard-gateway");
      let raw = result["ai-guard-gateway"];
      if (!raw) return "http://127.0.0.1:8080";
      if (!raw.startsWith("http://") && !raw.startsWith("https://"))
        raw = "http://" + raw;
      return raw;
    } catch {
      return "http://127.0.0.1:8080";
    }
  }

  function showActionToast(userAction) {
    const labels = {
      DESENSITIZE_AND_SEND: "已选择发送脱敏内容",
      SEND_ORIGINAL: "已选择发送原文",
      CANCEL: "已取消发送",
      CONFIG_NEEDED: "请先配置安全网关地址",
    };
    const msg = labels[userAction] || userAction;
    const toast = document.createElement("div");
    toast.textContent = "[AI安全助手] " + msg;
    toast.style.cssText =
      "position:fixed;bottom:24px;right:24px;background:#1e293b;color:#f1f5f9;padding:10px 20px;border-radius:8px;z-index:2147483647;font-size:14px;font-family:sans-serif;box-shadow:0 4px 12px rgba(0,0,0,.3);opacity:0;transition:opacity .3s;pointer-events:none";
    document.body.appendChild(toast);
    requestAnimationFrame(() => {
      toast.style.opacity = "1";
    });
    setTimeout(() => {
      toast.style.opacity = "0";
      setTimeout(() => toast.remove(), 300);
    }, 2500);
  }

  function continueSend({ input, trigger, content }) {
    setEditableText(input, content);
    markBypass(input);
    if (trigger) markBypass(trigger);
    setTimeout(() => {
      if (trigger) {
        trigger.click();
        return;
      }
      const sendBtn = findSendButtonNearInput(input);
      if (sendBtn) {
        markBypass(sendBtn);
        sendBtn.click();
        return;
      }
      dispatchEnter(input);
    }, 50);
  }

  // ====== 文本输入框操作 ======
  function getEditableText(el) {
    if (!el) return "";
    if (
      el.isContentEditable ||
      (el.nodeName === "DIV" && el.getAttribute("contenteditable") === "true")
    ) {
      return (el.textContent || "").trim();
    }
    if (
      el.nodeName === "TEXTAREA" ||
      (el.nodeName === "INPUT" &&
        (el.type === "text" || el.type === "search" || !el.type))
    ) {
      return (el.value || "").trim();
    }
    return "";
  }

  function setEditableText(el, text) {
    if (!el) return;
    if (
      el.isContentEditable ||
      (el.nodeName === "DIV" && el.getAttribute("contenteditable") === "true")
    ) {
      el.textContent = text;
      el.dispatchEvent(new Event("input", { bubbles: true }));
      return;
    }
    if (el.nodeName === "TEXTAREA" || el.nodeName === "INPUT") {
      const nativeSetter =
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")
          ?.set ||
        Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")
          ?.set;
      if (nativeSetter) nativeSetter.call(el, text);
      else el.value = text;
      el.dispatchEvent(new Event("input", { bubbles: true }));
      el.dispatchEvent(new Event("change", { bubbles: true }));
    }
  }

  function findEditable(el) {
    while (el) {
      if (el.isContentEditable) return el;
      if (
        (el.nodeName === "TEXTAREA" || el.nodeName === "INPUT") &&
        (el.type === "text" || el.type === "search" || !el.type)
      )
        return el;
      if (el.getAttribute && el.getAttribute("contenteditable") === "true")
        return el;
      el = el.parentElement;
    }
    return null;
  }

  function dispatchEnter(input) {
    input.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Enter",
        code: "Enter",
        keyCode: 13,
        which: 13,
        bubbles: true,
        cancelable: true,
      }),
    );
  }

  // ====== 辅助 ======
  function isChromeRuntimeAvailable() {
    try {
      return !!(chrome && chrome.runtime && chrome.runtime.id);
    } catch {
      return false;
    }
  }
  function shouldBypass(el) {
    return bypassElements.has(el);
  }
  function markBypass(el) {
    bypassElements.set(el, true);
  }
  function looksAlreadyDesensitized(t) {
    return t && /\[[A-Z_]+_\d+\]/.test(t);
  }
  function guessLanguage(t) {
    if (!t) return "zh";
    return (t.match(/[\u4e00-\u9fa5]/g) || []).length > t.length * 0.3
      ? "zh"
      : "en";
  }
})();
