const SEND_KEYWORDS = ["send", "发送", "submit", "提交", "ask", "message"];
const bypassElements = new WeakMap();

document.addEventListener("click", handleClick, true);
document.addEventListener("keydown", handleKeydown, true);

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
    const response = await chrome.runtime.sendMessage({
      type: "gateway-review-input",
      payload: {
        content,
        language: guessLanguage(content),
      },
    });

    if (!response?.ok) {
      const allow = window.confirm(
        `[AI 输入安全助手]\n安全网关检查失败：${response?.error ?? "未知错误"}\n\n点击“确定”继续原文发送，点击“取消”终止发送。`,
      );
      if (allow) {
        continueSend({ input, trigger, content });
      }
      return;
    }

    const result = response.result;
    const detectedEntities = Array.isArray(result?.detectedEntities)
      ? result.detectedEntities
      : [];
    const desensitizedContent = result?.desensitizedContent || content;

    if (!detectedEntities.length || desensitizedContent === content) {
      continueSend({ input, trigger, content });
      return;
    }

    const matchedTypes = [
      ...new Set(
        detectedEntities.map((entity) => entity?.type).filter(Boolean),
      ),
    ];
    const preview =
      desensitizedContent.length > 140
        ? `${desensitizedContent.slice(0, 140)}...`
        : desensitizedContent;
    const confirmed = window.confirm(
      `[AI 输入安全助手]\n检测到可能敏感信息：${matchedTypes.join("、") || "未知类型"}\n\n点击“确定”后将使用脱敏内容发送：\n${preview}\n\n点击“取消”则终止本次发送。`,
    );

    if (confirmed) {
      continueSend({
        input,
        trigger,
        content: desensitizedContent,
      });
    }
  } catch (error) {
    console.error("[AI 输入安全助手] 发送前检查失败", error);
  }
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
  const until = bypassElements.get(element);
  if (!until) {
    return false;
  }
  if (until < Date.now()) {
    bypassElements.delete(element);
    return false;
  }
  bypassElements.delete(element);
  return true;
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
