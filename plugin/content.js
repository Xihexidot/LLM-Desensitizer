const SEND_KEYWORDS = ["send", "发送", "submit", "提交", "ask", "message"];
const bypassElements = new WeakMap();

document.addEventListener("click", handleClick, true);
document.addEventListener("keydown", handleKeydown, true);

function detectCurrentProvider() {
  try {
    const host = window.location.hostname || "";
    if (host.includes("deepseek")) return "DeepSeek";
    if (host.includes("chatgpt") || host.includes("openai")) return "ChatGPT";
    if (host.includes("kimi") || host.includes("moonshot")) return "Kimi";
    if (host.includes("tongyi") || host.includes("qwen")) return "通义千问";
    if (host.includes("doubao") || host.includes("volces")) return "豆包";
    if (host.includes("claude") || host.includes("anthropic")) return "Claude";
    if (host.includes("gemini") || host.includes("google")) return "Gemini";
    if (host.includes("wenxin") || host.includes("baidu")) return "文心一言";
    if (host.includes("hunyuan")) return "混元";
    if (host.includes("perplexity")) return "Perplexity";
    return host || "未知平台";
  } catch {
    return "未知平台";
  }
}

function handleClick(event) {
  const activeInput = findEditable(document.activeElement);
  const trigger = findSendTrigger(event.target, activeInput);
  if (!trigger || shouldBypass(trigger)) {
    return;
  }

  const input = findRelatedInput(trigger, activeInput);
  if (!input || shouldBypass(input)) {
    return;
  }

  const content = getEditableText(input);
  if (!content) {
    return;
  }

  event.preventDefault();
  event.stopImmediatePropagation();
  reviewAndContinue({ input, trigger, content });
}

function handleKeydown(event) {
  if (event.key !== "Enter" || event.shiftKey || event.isComposing) {
    return;
  }

  const input = findEditable(event.target);
  if (!input || shouldBypass(input)) {
    return;
  }

  const content = getEditableText(input);
  if (!content) {
    return;
  }

  event.preventDefault();
  event.stopImmediatePropagation();
  reviewAndContinue({ input, trigger: null, content });
}

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
        `[AI 输入安全助手]\n安全网关检查失败：${response?.error ?? "未知错误"}\n\n点击"确定"继续原文发送，点击"取消"终止发送。`,
      );
      if (allow) {
        continueSend({ input, trigger, content });
      }
      return;
    }

    const result = response.result;
    const auditEventId = result?.auditEventId;
    const detectedEntities = Array.isArray(result?.detectedEntities)
      ? result.detectedEntities
      : [];
    const desensitizedContent = result?.desensitizedContent || content;

    if (!detectedEntities.length || desensitizedContent === content) {
      continueSend({ input, trigger, content });
      return;
    }

    const choice = await Popup.show({
      detectedEntities,
      desensitizedContent,
      originalContent: content,
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
    console.error("[AI 输入安全助手] 发送前检查失败", error);
  }
}

function notifyConfirmAction(auditEventId, userAction) {
  if (!auditEventId) return;
  getBaseUrl().then(baseUrl => {
    fetch(`${baseUrl}/plugin/confirm-action`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ auditEventId, userAction }),
    }).catch(() => {});
  }).catch(() => {});
  showActionToast(userAction);
}

async function getBaseUrl() {
  try {
    const result = await chrome.storage.local.get("ai-guard-gateway");
    let raw = result["ai-guard-gateway"];
    if (!raw) return "http://127.0.0.1:8080";
    if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
      raw = "http://" + raw;
    }
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
  };
  const msg = labels[userAction] || userAction;
  const toast = document.createElement("div");
  toast.textContent = `[AI安全助手] ${msg}`;
  toast.style.cssText =
    "position:fixed;bottom:24px;right:24px;background:#1e293b;color:#f1f5f9;padding:10px 20px;border-radius:8px;z-index:2147483647;font-size:14px;font-family:sans-serif;box-shadow:0 4px 12px rgba(0,0,0,0.3);opacity:0;transition:opacity 0.3s;pointer-events:none";
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
  if (trigger) {
    markBypass(trigger);
  }

  window.setTimeout(() => {
    if (trigger) {
      trigger.click();
      return;
    }

    const sendButton = findSendButtonNear(input);
    if (sendButton) {
      markBypass(sendButton);
      sendButton.click();
      return;
    }

    dispatchEnter(input);
  }, 0);
}

function findSendTrigger(target, preferredInput) {
  if (!(target instanceof Element)) {
    return null;
  }

  const candidate = target.closest(
    'button, [role="button"], input[type="submit"]',
  );
  if (candidate) {
    const hintText = [
      candidate.getAttribute("aria-label"),
      candidate.getAttribute("title"),
      candidate.textContent,
      candidate.id,
      candidate.className,
    ]
      .filter(Boolean)
      .join(" ")
      .toLowerCase();

    if (SEND_KEYWORDS.some((keyword) => hintText.includes(keyword))) {
      return candidate;
    }
  }

  const fallbackCandidate = target.closest(
    'button, [role="button"], [tabindex], svg, path, div',
  );
  if (!fallbackCandidate) {
    return null;
  }

  const relatedInput =
    preferredInput && getEditableText(preferredInput)
      ? preferredInput
      : findEditable(document.activeElement);
  if (!relatedInput || !getEditableText(relatedInput)) {
    return null;
  }

  const clickable =
    fallbackCandidate.closest('button, [role="button"], [tabindex], div') ||
    fallbackCandidate;
  return isPossibleIconSendTrigger(clickable, relatedInput) ? clickable : null;
}

function findRelatedInput(trigger, preferredInput) {
  if (preferredInput && getEditableText(preferredInput)) {
    return preferredInput;
  }

  const container =
    trigger.closest("form, main, section, div") || document.body;
  const inputs = container.querySelectorAll(
    'textarea, input[type="text"], [contenteditable="true"], [contenteditable=""], [role="textbox"]',
  );
  for (const input of inputs) {
    const editable = findEditable(input);
    const content = editable ? getEditableText(editable) : "";
    if (content) {
      return editable;
    }
  }

  return findEditable(document.activeElement);
}

function findSendButtonNear(input) {
  const container = input.closest("form, main, section, div") || document.body;
  const candidates = container.querySelectorAll(
    'button, [role="button"], input[type="submit"], [tabindex], div',
  );
  for (const candidate of candidates) {
    if (candidate !== input && findSendTrigger(candidate, input)) {
      return candidate;
    }
  }
  return null;
}

function findEditable(target) {
  if (!(target instanceof Element)) {
    return null;
  }

  if (isEditable(target)) {
    return target;
  }

  return target.closest(
    'textarea, input[type="text"], [contenteditable="true"], [contenteditable=""], [role="textbox"]',
  );
}

function isEditable(element) {
  if (!(element instanceof Element)) {
    return false;
  }

  if (element instanceof HTMLTextAreaElement) {
    return true;
  }

  if (element instanceof HTMLInputElement) {
    return ["text", "search"].includes(element.type);
  }

  const role = element.getAttribute("role");
  return element.isContentEditable || role === "textbox";
}

function getEditableText(element) {
  if (!element) {
    return "";
  }

  if (
    element instanceof HTMLInputElement ||
    element instanceof HTMLTextAreaElement
  ) {
    return element.value.trim();
  }

  return (element.innerText || element.textContent || "").trim();
}

function setEditableText(element, value) {
  if (
    element instanceof HTMLInputElement ||
    element instanceof HTMLTextAreaElement
  ) {
    element.focus();
    element.value = value;
    element.dispatchEvent(new Event("input", { bubbles: true }));
    element.dispatchEvent(new Event("change", { bubbles: true }));
    return;
  }

  if (
    element &&
    (element.isContentEditable || element.getAttribute("role") === "textbox")
  ) {
    element.focus();
    element.textContent = value;
    element.dispatchEvent(
      new InputEvent("input", {
        bubbles: true,
        data: value,
        inputType: "insertText",
      }),
    );
  }
}

function dispatchEnter(element) {
  markBypass(element);
  const event = new KeyboardEvent("keydown", {
    key: "Enter",
    code: "Enter",
    which: 13,
    keyCode: 13,
    bubbles: true,
  });
  element.dispatchEvent(event);
}

function markBypass(element) {
  bypassElements.set(element, Date.now() + 1000);
}

function shouldBypass(element) {
  let current = element;
  let depth = 0;
  while (current instanceof Element && depth < 4) {
    const until = bypassElements.get(current);
    if (until) {
      if (until < Date.now()) {
        bypassElements.delete(current);
        return false;
      }
      bypassElements.delete(current);
      return true;
    }
    current = current.parentElement;
    depth += 1;
  }
  return false;
}

function isPossibleIconSendTrigger(candidate, input) {
  if (!(candidate instanceof Element) || !(input instanceof Element)) {
    return false;
  }

  const textHint = [
    candidate.getAttribute("aria-label"),
    candidate.getAttribute("title"),
    candidate.textContent,
  ]
    .filter(Boolean)
    .join(" ")
    .trim();
  const hasGraphic = !!candidate.querySelector?.("svg, path, img");
  const tagName = candidate.tagName?.toLowerCase() || "";
  const looksClickable =
    tagName === "button" ||
    tagName === "div" ||
    candidate.getAttribute("role") === "button" ||
    candidate.hasAttribute("tabindex");
  const sameContainer = hasSharedNearbyContainer(candidate, input);

  return (
    sameContainer && looksClickable && (hasGraphic || textHint.length <= 2)
  );
}

function hasSharedNearbyContainer(left, right) {
  const leftAncestors = collectAncestors(left, 8);
  const rightAncestors = new Set(collectAncestors(right, 8));
  return leftAncestors.some((ancestor) => rightAncestors.has(ancestor));
}

function collectAncestors(element, depthLimit) {
  const ancestors = [];
  let current = element;
  let depth = 0;
  while (current instanceof Element && depth < depthLimit) {
    ancestors.push(current);
    current = current.parentElement;
    depth += 1;
  }
  return ancestors;
}

function guessLanguage(content) {
  return /[\u4e00-\u9fa5]/.test(content) ? "zh" : "en";
}

function looksAlreadyDesensitized(content) {
  return /\[(?:PHONE|ID_CARD|BANK_CARD|EMAIL|ADDRESS|NAME|PERSON|MASKED)_[0-9]+\]/.test(
    content,
  );
}

function isChromeRuntimeAvailable() {
  return (
    typeof chrome !== "undefined" &&
    !!chrome.runtime &&
    !!chrome.runtime.id &&
    typeof chrome.runtime.sendMessage === "function"
  );
}
