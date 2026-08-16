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

      // 保存脱敏映射（占位符 → 明文），供"一键复原"将 AI 回复中的脱敏标记还原为原始数据
      if (result && typeof result.maskMapping === "object") {
        saveMaskMapping(result.maskMapping);
      }

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
        // 发送脱敏内容后注入"一键复原"悬浮按钮：等待 AI 返回后一键还原完整原始内容
        showRestoreFAB();
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

  // ====== 一键复原：将 AI 回复中的脱敏标记还原为发送前的原始数据 ======
  // 映射存放于会话级 chrome.storage.session，content script 无权直接访问，
  // 统一经 background（特权上下文）中转读写，浏览器关闭即清空。

  function saveMaskMapping(maskMapping) {
    if (!maskMapping || typeof maskMapping !== "object") return;
    chrome.runtime
      .sendMessage({ type: "save-mask-mapping", payload: maskMapping })
      .catch((e) => log("saveMaskMapping failed:", e?.message));
  }

  function loadMaskMapping() {
    return new Promise((resolve) => {
      chrome.runtime
        .sendMessage({ type: "load-mask-mapping" })
        .then((r) => resolve(r?.maskMapping || {}))
        .catch(() => resolve({}));
    });
  }

  // ====== 一键复原：对话区定位 + 消息单元抽取 ======
  // 目标：仅提取当前活跃对话的核心内容，排除侧边栏历史标题、系统提示、
  // 功能按钮、脱敏提示等无关元素。
  // 定位策略（简洁版，无复杂打分）：
  //  1. 锚点 = 最后一个含脱敏标记的元素（AI 回复必然复述标记，天然指向最新回复）；
  //  2. 从锚点向上回溯到"含多个消息单元"的对话容器（遇侧边栏特征即止）；
  //  3. 以锚点为界截断抽取，仅保留最新一轮"用户提问 + AI 回复"。
  // 锚点法天然规避侧边栏：侧边栏与对话区是兄弟节点，不可能成为锚点的祖先，
  // 因此无论页面如何布局，抽取范围都不会扩散到侧边栏。

  /** 消息单元选择器：覆盖 DeepSeek / ChatGPT / 豆包 / Gemini / Kimi 各系 DOM */
  const MESSAGE_SELECTOR = [
    "[data-message-author-role]",
    "[data-testid='conversation-turn']",
    "[data-testid='message-block-container']",
    "[data-testid='chat_message_item']",
    ".ds-message",
    ".ds-user-message",
    ".ds-assistant-message",
    ".assistant-message",
    ".user-message",
    ".message-content",
    ".ds-markdown",
    ".markdown",
    ".prose",
    ".bg-g-send-msg-bubble-bg",
  ].join(", ");

  /** 是否为插件自身 DOM（悬浮按钮 / 复原面板 / 提示层），提取与定位时必须排除 */
  function isPluginDom(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.closest) {
      try {
        if (
          el.closest(
            "#ai-guard-restore-panel, #ai-guard-restore-fab, [data-ai-guard='true']",
          )
        ) {
          return true;
        }
      } catch {
        /* 忽略非法选择器 */
      }
    }
    if (el.id && el.id.indexOf("ai-guard-") === 0) return true;
    if (el.getAttribute && el.getAttribute("data-ai-guard") === "true") {
      return true;
    }
    return false;
  }

  /** 是否为非对话结构元素（侧边栏 / 导航 / 顶栏等），上溯定位时作为对话边界 */
  function isNonConversationEl(el) {
    const tag = (el.tagName || "").toLowerCase();
    if (["aside", "nav", "header", "footer"].includes(tag)) return true;
    const cls = typeof el.className === "string" ? String(el.className) : "";
    const hay = (String(el.id || "") + " " + cls).toLowerCase();
    return [
      "sidebar",
      "side-bar",
      "sidenav",
      "navbar",
      "toolbar",
      "menubar",
      "chat-list",
      "session-list",
      "conversation-list",
      "history",
      "topbar",
    ].some((h) => hay.indexOf(h) !== -1);
  }

  /** 元素简述（tag#id.class），用于日志与调试信息 */
  function descEl(el) {
    if (!el) return "null";
    if (el === document) return "document";
    if (el === document.body) return "document.body";
    const tag = (el.tagName || "").toLowerCase();
    const cls = typeof el.className === "string" ? String(el.className) : "";
    return (
      tag +
      (el.id ? "#" + el.id : "") +
      (cls ? "." + cls.trim().split(/\s+/).join(".") : "")
    );
  }

  /** 是否为可见元素（隐藏节点不参与抽取） */
  function isVisible(el) {
    if (!el) return false;
    if (el.hidden) return false;
    if (el.getAttribute && el.getAttribute("aria-hidden") === "true")
      return false;
    try {
      const st = window.getComputedStyle(el);
      if (st.display === "none" || st.visibility === "hidden") return false;
      if (Number(st.opacity || "1") === 0) return false;
    } catch {
      /* 取不到样式时视为可见 */
    }
    return true;
  }

  /** 非可见数据节点标签（script/style 内的文本是序列化数据，不能作为业务锚点） */
  const INVISIBLE_TEXT_TAGS = new Set([
    "SCRIPT",
    "STYLE",
    "NOSCRIPT",
    "TEMPLATE",
  ]);

  /** 兜底：整页寻找脱敏标记最集中的元素（页面无消息节点结构时使用） */
  function findMaskedAreaFallback() {
    let best = null;
    let bestN = 0;
    const all = document.querySelectorAll("body *");
    for (const el of all) {
      if (
        isPluginDom(el) ||
        isNonConversationEl(el) ||
        INVISIBLE_TEXT_TAGS.has((el.tagName || "").toUpperCase())
      ) {
        continue;
      }
      const m = (el.textContent || "").match(/\[\w+_\d+\]/g);
      if (m && m.length > bestN) {
        bestN = m.length;
        best = el;
      }
    }
    return best;
  }

  /** 全文档查找最后一个含脱敏标记的文本所在元素（业务锚点：AI 回复必然复述标记） */
  function findMarkAnchor() {
    const MASK_RE = /\[[^\[\]]+_\d+\]/;
    const walker = document.createTreeWalker(
      document.body,
      NodeFilter.SHOW_TEXT,
      {
        acceptNode(node) {
          const p = node.parentElement;
          if (!p || INVISIBLE_TEXT_TAGS.has(p.tagName)) {
            return NodeFilter.FILTER_REJECT;
          }
          return NodeFilter.FILTER_ACCEPT;
        },
      },
    );
    let node = null;
    let anchor = null;
    while ((node = walker.nextNode())) {
      const p = node.parentElement;
      if (p && !isPluginDom(p) && MASK_RE.test(node.data)) {
        anchor = p;
      }
    }
    return anchor;
  }

  /** 元素内含多少个消息单元（可见、非插件 DOM） */
  function countMessageUnits(el) {
    return Array.from(el.querySelectorAll(MESSAGE_SELECTOR)).filter(
      (n) => !isPluginDom(n) && isVisible(n),
    ).length;
  }

  /**
   * 定位当前活跃对话区域（简洁版，无复杂打分）：
   * 1. 锚点 = 最后一个含脱敏标记的元素（AI 回复必然复述标记，天然指向最新回复）；
   *    页面无标记时退化为最后一个用户消息 / 最后一条消息；
   * 2. 从锚点向上回溯：遇到含 ≥2 个消息单元的祖先即采用（对话容器），
   *    遇 aside/nav/侧边栏特征立即停止（对话边界）；
   * 3. 无任何锚点时回退到 main / 脱敏标记最集中的元素；
   *    绝不回退到 document.body，避免侧边栏等无关内容混入复原结果。
   */
  function findActiveConversationArea() {
    let anchor = findMarkAnchor();
    if (!anchor) {
      const all = collectMessageNodes(document);
      if (all.length > 0) {
        anchor = all[all.length - 1];
        for (let i = all.length - 1; i >= 0; i--) {
          if (isUserMessage(all[i])) {
            anchor = all[i];
            break;
          }
        }
      }
    }
    if (!anchor) {
      return (
        findMaskedAreaFallback() ||
        document.querySelector("main, [role='main']") ||
        null
      );
    }
    let cur = anchor.parentElement;
    let area = cur || anchor;
    while (cur && cur !== document.body) {
      if (isNonConversationEl(cur)) break; // 已到对话边界，停留于边界之下
      const tag = (cur.tagName || "").toLowerCase();
      if (
        tag === "main" ||
        cur.getAttribute("role") === "main" ||
        countMessageUnits(cur) >= 2
      ) {
        area = cur;
        break;
      }
      area = cur;
      cur = cur.parentElement;
    }
    log("locate area ->", descEl(area), "| anchor ->", descEl(anchor));
    return area;
  }

  /**
   * 按消息单元收集容器内可见消息节点（去重嵌套，保持文档顺序）。
   */
  function collectMessageNodes(container) {
    if (!container) return [];
    const all = Array.from(container.querySelectorAll(MESSAGE_SELECTOR));
    if (container.matches && container.matches(MESSAGE_SELECTOR)) {
      all.unshift(container);
    }
    const out = [];
    for (const node of all) {
      if (isPluginDom(node) || !isVisible(node)) continue;
      // 嵌套结构（如 .markdown 位于 .message-content 内）只取外层，避免重复
      let anc = node.parentElement;
      let covered = false;
      while (anc && anc !== container) {
        if (anc.matches && anc.matches(MESSAGE_SELECTOR)) {
          covered = true;
          break;
        }
        anc = anc.parentElement;
      }
      if (covered) continue;
      out.push(node);
    }
    return out;
  }

  /** 判断消息节点是否为"用户消息"（data-message-author-role=user、user 类名或豆包用户气泡） */
  function isUserMessage(node) {
    if (!node || node.nodeType !== 1) return false;
    if (
      node.getAttribute &&
      node.getAttribute("data-message-author-role") === "user"
    ) {
      return true;
    }
    const cls =
      node.className && typeof node.className === "string"
        ? String(node.className)
        : "";
    if (/(^|[\s-])user-?/.test(cls)) return true;
    // 豆包用户气泡：class 含 bg-g-send-msg-bubble（发送气泡）；助手消息不带 send
    if (/bg-g-send-msg-bubble/.test(cls)) return true;
    // 助手气泡（豆包老结构）不匹配 send-msg-bubble，保持默认 false
    return false;
  }

  /** 是否为思考/推理残留块（DeepSeek 深度思考、ChatGPT reasoning 等），抽取时整块剔除 */
  function isReasoningNode(el) {
    if (!el || el.nodeType !== 1) return false;
    const cls =
      el.className && typeof el.className === "string"
        ? String(el.className)
        : "";
    const hay = (
      String(el.id || "") +
      " " +
      cls +
      " " +
      (el.getAttribute("aria-label") || "")
    ).toLowerCase();
    return /think|reasoning|deep-?think|分析|思考/.test(hay);
  }

  /**
   * 取消息文本：剔除 DeepSeek 深度思考 / 推理残留块后再返回（保留换行）。
   * 推理内容为任意文本、无法用行级正则穷举，必须在 DOM 层按块剔除：
   * 命中思考标记（class/id/aria-label 含 think/reasoning/思考，或折叠面板首行
   * 为"已深度思考/思考过程"）的 details 及其内容整体排除。
   */
  function safeMessageText(node) {
    const full = (node.innerText || node.textContent || "").trim();
    if (!full) return "";
    let text = full;
    const blocks = node.querySelectorAll(
      "details, [class*='think' i], [id*='think' i], [class*='reasoning' i], [aria-label*='思考'], [aria-label*='think' i]",
    );
    for (const block of blocks) {
      if (!isVisible(block) || isPluginDom(block)) continue;
      const label = (block.innerText || "").slice(0, 30);
      const byLabel = /深度思考|思考过程|已深度思考/.test(label);
      if (!isReasoningNode(block) && !byLabel) continue;
      const rt = (block.innerText || "").trim();
      if (rt && text.includes(rt)) text = text.replace(rt, "");
    }
    return text.replace(/\n{3,}/g, "\n\n").trim();
  }

  /** 容器纯文本（克隆后剔除思考/推理残留块，避免直接修改页面 DOM） */
  function containerTextWithoutThinking(container) {
    const clone = container.cloneNode(true);
    clone
      .querySelectorAll(
        "details, [class*='think' i], [id*='think' i], [class*='reasoning' i], [aria-label*='思考'], [aria-label*='think' i]",
      )
      .forEach((block) => {
        const label = (block.innerText || "").slice(0, 30);
        const byLabel = /深度思考|思考过程|已深度思考/.test(label);
        if (isReasoningNode(block) || byLabel) block.remove();
      });
    return (clone.innerText || "").replace(/\n{3,}/g, "\n\n").trim();
  }

  /**
   * 抽取"最近一条对话"（一键复原专用）：
   * 从最后一个用户消息开始截取到末尾（含其 AI 回复），
   * 过滤更早的历史消息、标题冗余与 DeepSeek 思考模式残留，
   * 仅保留最新单条对话的用户提问与 AI 回复纯文本。
   * 消息结构无法识别时退化为容器纯文本（容器已精确限定为对话区，
   * 不含侧边栏；配合行级噪声过滤与思考块剔除保证输出干净）。
   */
  function extractLatestTurnText(container) {
    if (!container) return "";
    const nodes = collectMessageNodes(container);
    if (nodes.length === 0) {
      return containerTextWithoutThinking(container);
    }
    let start = nodes.length;
    for (let i = nodes.length - 1; i >= 0; i--) {
      if (isUserMessage(nodes[i])) {
        start = i;
        break;
      }
    }
    // 无用户消息时仅取最后一条消息（如连续回复/思考续写流）；
    // 否则取最后一个用户提问及其后的全部回复（通常为单条 AI 回复）。
    const latest =
      start === nodes.length ? [nodes[nodes.length - 1]] : nodes.slice(start);
    const parts = [];
    const seen = new Set();
    for (const node of latest) {
      const text = safeMessageText(node);
      if (!text || seen.has(text)) continue;
      seen.add(text);
      parts.push(text);
    }
    return parts.join("\n");
  }

  let restoreFabEl = null;
  let restorePanelEl = null;

  /** 注入"一键复原"悬浮按钮（幂等：已存在则不重复创建） */
  function showRestoreFAB() {
    if (restoreFabEl && document.body.contains(restoreFabEl)) return;
    const fab = document.createElement("button");
    fab.id = "ai-guard-restore-fab";
    fab.type = "button";
    fab.textContent = "一键复原";
    fab.title = "将 AI 回复中的脱敏标记还原为发送前的完整原始内容";
    fab.style.cssText =
      "position:fixed;bottom:80px;right:24px;z-index:2147483646;background:#059669;color:#fff;" +
      "border:none;border-radius:999px;padding:10px 18px;font-size:14px;font-family:sans-serif;" +
      "cursor:pointer;box-shadow:0 4px 14px rgba(5,150,105,.35);transition:transform .15s;";
    fab.addEventListener(
      "mouseenter",
      () => (fab.style.transform = "scale(1.05)"),
    );
    fab.addEventListener(
      "mouseleave",
      () => (fab.style.transform = "scale(1)"),
    );
    fab.addEventListener("click", onRestoreClick);
    restoreFabEl = fab;
    document.body.appendChild(fab);
    showActionToast("已注入一键复原按钮");
  }

  /** 定位容器内最后一个用户消息节点（无用户消息则取最后一条消息） */
  function lastUserMessageNode(area) {
    const nodes = collectMessageNodes(area || document);
    for (let i = nodes.length - 1; i >= 0; i--) {
      if (isUserMessage(nodes[i])) return nodes[i];
    }
    return nodes[nodes.length - 1] || null;
  }

  /** 收集一键复原的调试信息（供用户一键复制反馈，辅助真实站点排障） */
  function buildDebugInfo(area, rawText, decoded) {
    const nodes = collectMessageNodes(area);
    return {
      url: location.href,
      area: descEl(area),
      anchor: descEl(findMarkAnchor() || lastUserMessageNode(area)),
      messageCount: nodes.length,
      messages: nodes.slice(0, 30).map((n) => ({
        el: descEl(n),
        text: (n.innerText || "").replace(/\s+/g, " ").slice(0, 60),
      })),
      rawTextLength: rawText.length,
      rawTextPreview: rawText.slice(0, 600),
      restoredTextPreview: (decoded.text || "").slice(0, 600),
      replacedCount: decoded.replacedCount,
    };
  }

  async function onRestoreClick() {
    const maskMapping = await loadMaskMapping();
    const area = findActiveConversationArea();
    if (!area) {
      showActionToast("未定位到对话区域，请确认页面已加载当前对话");
      return;
    }
    const rawText = extractLatestTurnText(area);
    if (!rawText) {
      showActionToast("未提取到对话内容，请确认页面已加载当前对话");
    }
    const decoded = AIGuardDecode.decodeWithHighlights(rawText, maskMapping);
    const debugInfo = buildDebugInfo(area, rawText, decoded);
    log("restore debug:", debugInfo);
    showRestorePanel(decoded, debugInfo);
  }

  /** 展示"完整原始内容"复原面板：高亮展示还原结果，可复制原文 */
  function showRestorePanel(decoded, debugInfo) {
    if (restorePanelEl && restorePanelEl.parentNode) {
      restorePanelEl.remove();
    }
    const panel = document.createElement("div");
    panel.id = "ai-guard-restore-panel";
    panel.style.cssText =
      "position:fixed;right:24px;bottom:130px;width:min(520px,calc(100vw - 48px));max-height:60vh;" +
      "background:#ffffff;border:1px solid #d1fae5;border-radius:12px;box-shadow:0 8px 30px rgba(0,0,0,.18);" +
      "z-index:2147483647;display:flex;flex-direction:column;overflow:hidden;font-family:sans-serif;";

    const header = document.createElement("div");
    header.style.cssText =
      "display:flex;align-items:center;gap:8px;padding:10px 14px;background:#ecfdf5;" +
      "border-bottom:1px solid #d1fae5;font-size:13px;color:#065f46;font-weight:600;";
    const title = document.createElement("span");
    title.style.cssText = "flex:1;";
    title.textContent =
      decoded.replacedCount > 0
        ? `完整原始内容（已还原 ${decoded.replacedCount} 处脱敏标记）`
        : "完整原始内容（未发现可还原的脱敏标记）";
    const btnCopy = document.createElement("button");
    btnCopy.id = "agrCopy";
    btnCopy.textContent = "复制原文";
    btnCopy.style.cssText =
      "background:#059669;color:#fff;border:none;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px;";
    btnCopy.addEventListener("click", () =>
      copyRestoredText(decoded.text, btnCopy),
    );
    const btnDebug = document.createElement("button");
    btnDebug.id = "agrDebug";
    btnDebug.textContent = "复制调试信息";
    btnDebug.title = "复制定位与抽取日志，便于向开发者反馈问题";
    btnDebug.style.cssText =
      "background:#e2e8f0;color:#475569;border:none;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px;";
    btnDebug.addEventListener("click", () =>
      copyRestoredText(
        JSON.stringify(debugInfo || {}, null, 2),
        btnDebug,
        "已复制调试信息",
        "复制调试信息",
      ),
    );
    const btnClose = document.createElement("button");
    btnClose.id = "agrClose";
    btnClose.textContent = "关闭";
    btnClose.style.cssText =
      "background:#e2e8f0;color:#475569;border:none;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px;";
    btnClose.addEventListener("click", () => panel.remove());
    header.append(title, btnCopy, btnDebug, btnClose);

    const body = document.createElement("div");
    body.id = "agrBody";
    body.style.cssText =
      "padding:12px 14px;overflow:auto;font-size:13px;line-height:1.7;color:#1e293b;white-space:pre-wrap;word-break:break-all;";
    body.innerHTML = decoded.html; // decodeWithHighlights 已对所有动态内容转义，仅高亮 <mark>

    const style = document.createElement("style");
    style.textContent =
      "#ai-guard-restore-panel mark{background:#fde68a;color:#78350f;border-radius:3px;padding:0 2px;}";

    panel.append(style, header, body);
    restorePanelEl = panel;
    document.body.appendChild(panel);
  }

  function copyRestoredText(text, btn, doneText, idleText) {
    const done = () => {
      btn.textContent = doneText || "已复制";
      setTimeout(() => (btn.textContent = idleText || "复制原文"), 1200);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard
        .writeText(text)
        .then(done)
        .catch(() => fallbackCopy(text, done));
    } else {
      fallbackCopy(text, done);
    }
  }

  function fallbackCopy(text, done) {
    try {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      document.execCommand("copy");
      ta.remove();
      done();
    } catch {
      /* 忽略复制失败 */
    }
  }
})();
