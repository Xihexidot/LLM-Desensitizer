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

  /**
   * 查找 input 所在的功能容器（向上最多 10 层，找到第一个含按钮的祖先）。
   * 用于判断被点击的按钮是否属于该输入区域。
   */
  function findInputContainer(input) {
    if (!input) return null;
    let container = input.parentElement;
    for (let i = 0; i < 10 && container && container !== document.body; i++) {
      if (container.querySelector('button, [role="button"]')) return container;
      container = container.parentElement;
    }
    return null;
  }

  // 走到点击事件的按钮（包括其祖先 button）
  function resolveClickedButton(target) {
    if (!target) return null;
    let el = target;
    while (el && el !== document.body) {
      const tag = (el.tagName || "").toLowerCase();
      if (tag === "button") return el;
      if (el.getAttribute && el.getAttribute("role") === "button") return el;
      el = el.parentElement;
    }
    return null;
  }

  /**
   * 判断 clickedBtn 是否为 input 关联的发送按钮：
   * 策略：按钮在 input 的容器范围内，且不是明显的非发送功能按钮。
   */
  function isSendButtonForInput(clickedBtn, input) {
    const container = findInputContainer(input);
    if (!container) {
      log("isSendButtonForInput: no container found for input");
      return false;
    }
    if (!container.contains(clickedBtn)) {
      log("isSendButtonForInput: clickedBtn is outside input container");
      return false;
    }
    // 排除明显的非发送功能按钮（aria-label / title / class 关键词匹配）
    const label = (
      (clickedBtn.getAttribute("aria-label") || "") +
      " " +
      (clickedBtn.getAttribute("title") || "") +
      " " +
      (clickedBtn.className?.toString() || "")
    ).toLowerCase();
    const nonSendKeywords = [
      "upload",
      "attach",
      "file",
      "image",
      "photo",
      "clear",
      "delete",
      "close",
      "stop",
      "voice",
      "microphone",
      "emoji",
      "gif",
      "上传",
      "附件",
      "图片",
      "清空",
      "删除",
      "停止",
      "语音",
    ];
    const isNonSend = nonSendKeywords.some((k) => label.includes(k));
    if (isNonSend) {
      log(
        "isSendButtonForInput: skip non-send button, label snippet=",
        label.slice(0, 80),
      );
      return false;
    }
    return true;
  }

  function handleClick(event) {
    const clickedBtn = resolveClickedButton(event.target);
    if (!clickedBtn) {
      log(
        "handleClick skip: no button resolved from",
        event.target?.tagName,
        event.target?.className?.toString().slice(0, 40),
      );
      return;
    }
    if (shouldBypass(clickedBtn)) {
      log("handleClick skip: clickedBtn is bypass-marked");
      return;
    }

    const input = findMainInput();
    if (!input) {
      log("handleClick skip: no main input found on page");
      return;
    }
    if (shouldBypass(input)) {
      log("handleClick skip: input is bypass-marked");
      return;
    }

    if (!isSendButtonForInput(clickedBtn, input)) {
      log("handleClick skip: not a send button for this input", {
        btnTag: clickedBtn?.tagName,
        btnClass: clickedBtn?.className?.toString().slice(0, 40),
        btnLabel: clickedBtn?.getAttribute("aria-label"),
      });
      return;
    }

    const content = getEditableText(input);
    if (!content) {
      log("handleClick skip: input content is empty");
      return;
    }

    log(
      "click intercepted, provider=",
      detectCurrentProvider(),
      "contentLen=",
      content.length,
    );
    event.preventDefault();
    event.stopImmediatePropagation();
    reviewAndContinue({ input, trigger: clickedBtn, content });
  }

  function handleKeydown(event) {
    if (event.key !== "Enter") return;
    if (event.shiftKey) {
      log("handleKeydown skip: Shift+Enter, skip");
      return;
    }
    if (event.isComposing) {
      log("handleKeydown skip: IME composition in progress");
      return;
    }

    const input = findMainInput();
    if (!input) {
      log("handleKeydown skip: no main input found");
      return;
    }
    if (shouldBypass(input)) {
      log("handleKeydown skip: input is bypass-marked");
      return;
    }

    // 确认焦点或在输入框内部
    if (
      !input.contains(document.activeElement) &&
      document.activeElement !== input
    ) {
      log(
        "handleKeydown skip: focus mismatch, activeElement=",
        document.activeElement?.tagName,
        document.activeElement?.className?.toString().slice(0, 40),
      );
      return;
    }

    const content = getEditableText(input);
    if (!content) {
      log("handleKeydown skip: input content is empty");
      return;
    }

    log(
      "enter key intercepted, provider=",
      detectCurrentProvider(),
      "contentLen=",
      content.length,
    );
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
    const provider = detectCurrentProvider();
    log(
      "reviewAndContinue start, provider=",
      provider,
      "contentLen=",
      content?.length,
    );
    try {
      if (looksAlreadyDesensitized(content)) {
        log(
          "reviewAndContinue: content looks already desensitized, skip check → continueSend",
        );
        continueSend({ input, trigger, content });
        return;
      }
      if (!isChromeRuntimeAvailable()) {
        log(
          "reviewAndContinue: chrome.runtime unavailable, extension context may be invalidated",
        );
        window.alert(
          "[AI 输入安全助手] 插件上下文已失效，请刷新当前页面后重试。",
        );
        return;
      }

      log("reviewAndContinue: checking gateway configuration...");
      const hasGateway = await checkGatewayConfigured();
      log("reviewAndContinue: hasGateway=", hasGateway);
      if (!hasGateway) {
        const goConfig = window.confirm(
          "[AI 输入安全助手] 尚未配置安全网关地址，无法进行敏感信息检测。\n\n点击【确定】前往配置页面，点击【取消】本次继续发送原文。",
        );
        log(
          "reviewAndContinue: user chose goConfig=",
          goConfig,
          "(gateway not configured)",
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

      log(
        "reviewAndContinue: sending gateway-review-input, provider=",
        provider,
      );
      const response = await chrome.runtime.sendMessage({
        type: "gateway-review-input",
        payload: {
          content,
          language: guessLanguage(content),
          targetProvider: provider,
        },
      });

      log(
        "reviewAndContinue: gateway response ok=",
        response?.ok,
        "error=",
        response?.error,
      );
      if (!response?.ok) {
        log(
          "reviewAndContinue: gateway check failed, error detail:",
          response?.error,
        );
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

      log(
        "reviewAndContinue: detection result — riskLevel=",
        riskLevel,
        "decisionAction=",
        decisionAction,
        "entities count=",
        detectedEntities.length,
        "auditEventId=",
        auditEventId,
      );

      if (!detectedEntities.length || riskLevel === "NONE") {
        log(
          "reviewAndContinue: no sensitive entities or riskLevel=NONE → continueSend (clean pass)",
        );
        continueSend({ input, trigger, content });
        return;
      }

      log(
        "reviewAndContinue: showing Popup to user, entityTypes=",
        detectedEntities.map((e) => e.type),
      );
      const choice = await Popup.show({
        detectedEntities,
        desensitizedContent,
        originalContent: content,
        riskLevel,
        decisionAction,
        source: "gateway",
      });

      log("reviewAndContinue: user Popup choice=", choice);
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
      log("reviewAndContinue: uncaught error", {
        message: error?.message,
        stack: error?.stack,
        provider,
        contentLen: content?.length,
      });
    }
  }

  async function checkGatewayConfigured() {
    if (gatewayConfiguredCache === true) {
      log("checkGatewayConfigured: cache hit → true");
      return true;
    }
    try {
      const resp = await chrome.runtime.sendMessage({
        type: "check-gateway-status",
      });
      const configured = resp?.configured || false;
      log(
        "checkGatewayConfigured: background responded configured=",
        configured,
      );
      if (configured) gatewayConfiguredCache = true; // 只缓存"已配置"状态
      return configured;
    } catch (e) {
      log(
        "checkGatewayConfigured: sendMessage failed (background may be inactive):",
        e?.message,
      );
      return false; // 出错不缓存，下次还会重试
    }
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
      // Enter 键触发时无 trigger，从容器内找发送按钮（取最后一个，通常发送按钮在末尾）
      const container = findInputContainer(input);
      const allBtns = container
        ? Array.from(container.querySelectorAll('button, [role="button"]'))
        : [];
      const sendBtn =
        allBtns.filter((b) => isSendButtonForInput(b, input)).pop() || null;
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
      // 根据元素类型取对应的 native setter，避免跨类型调用导致 Illegal invocation
      const proto =
        el.nodeName === "TEXTAREA"
          ? HTMLTextAreaElement.prototype
          : HTMLInputElement.prototype;
      const nativeSetter = Object.getOwnPropertyDescriptor(proto, "value")?.set;
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
    // 匹配后端实际返回格式 [PHONE_NUMBER]、[ID_CARD] 等（不带数字后缀）
    return t && /\[[A-Z][A-Z_]*\]/.test(t);
  }
  function guessLanguage(t) {
    if (!t) return "zh";
    return (t.match(/[\u4e00-\u9fa5]/g) || []).length > t.length * 0.3
      ? "zh"
      : "en";
  }
})();
