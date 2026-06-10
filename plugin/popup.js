/**
 * AI 输入安全助手 — 检测弹窗模块
 * 取代浏览器原生 window.confirm，提供专业级安全检测结果展示界面。
 *
 * 用法：
 *   const result = await Popup.show({
 *     detectedEntities: [...],
 *     desensitizedContent: "...",
 *     originalContent: "...",
 *   });
 *   // result: "send" | "send-original" | "cancel"
 */
const Popup = (() => {
  "use strict";

  /* ---- 实体类型中文映射 ---- */
  const TYPE_LABELS = {
    PHONE_NUMBER:   "手机号",
    ID_CARD:        "身份证号",
    BANK_CARD:      "银行卡号",
    EMAIL:          "邮箱",
    ADDRESS:        "地址",
    NAME:           "姓名",
    PERSON:         "人名",
    ORGANIZATION:   "机构",
    LICENSE_PLATE:  "车牌号",
    PASSPORT:       "护照号",
    PASSWORD:       "密码",
    API_KEY:        "API Key",
    IP_ADDRESS:     "IP地址",
  };

  /* 类型 → 标签颜色 */
  function tagClassFor(type) {
    switch (type) {
      case "PHONE_NUMBER": case "ID_CARD": case "BANK_CARD":
      case "PASSWORD": case "API_KEY":
        return "ai-guard-tag-danger";
      case "EMAIL": case "IP_ADDRESS": case "LICENSE_PLATE": case "PASSPORT":
        return "ai-guard-tag-warn";
      case "ADDRESS": case "ORGANIZATION":
        return "ai-guard-tag-info";
      case "PERSON": case "NAME":
        return "ai-guard-tag-person";
      default:
        return "ai-guard-tag-warn";
    }
  }

  function labelFor(type) {
    return TYPE_LABELS[type] || type || "敏感信息";
  }

  /* ---- 构建 DOM ---- */
  function build(entities, desensitizedContent, originalContent) {
    const uniqueTypes = [...new Set(entities.map(e => e.type).filter(Boolean))];

    const tagsHtml = uniqueTypes.map(t =>
      `<span class="ai-guard-tag ${tagClassFor(t)}">${labelFor(t)}</span>`
    ).join("");

    const previewHtml = highlightPlaceholders(escapeHtml(desensitizedContent));
    const originalHtml = escapeHtml(originalContent);

    const container = document.createElement("div");
    container.className = "ai-guard-overlay";
    container.innerHTML = `
      <div class="ai-guard-modal" tabindex="0">
        <div class="ai-guard-header">
          <svg class="ai-guard-header-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
          <span class="ai-guard-header-title">检测到敏感信息</span>
        </div>
        <div class="ai-guard-body">
          <div class="ai-guard-section-title">识别类型 (${uniqueTypes.length})</div>
          <div class="ai-guard-tags">${tagsHtml}</div>
          <div class="ai-guard-section-title">脱敏后预览</div>
          <div class="ai-guard-preview-box">${previewHtml}</div>
          <div class="ai-guard-original-toggle" id="aiGuardToggle">
            <span class="ai-guard-toggle-arrow">&#9654;</span> 查看原文
          </div>
          <div class="ai-guard-original-content" id="aiGuardOriginal">${originalHtml}</div>
        </div>
        <div class="ai-guard-footer">
          <button id="aiGuardBtnCancel" class="ai-guard-btn ai-guard-btn-danger">取消发送</button>
          <button id="aiGuardBtnOriginal" class="ai-guard-btn ai-guard-btn-secondary">发送原文</button>
          <button id="aiGuardBtnSend" class="ai-guard-btn ai-guard-btn-primary">发送脱敏内容</button>
        </div>
      </div>`;

    return container;
  }

  /* 高亮占位符 [TYPE_N] */
  function highlightPlaceholders(html) {
    return html.replace(
      /(\[[A-Z_]+_[0-9]+\])/g,
      '<span class="ai-guard-placeholder">$1</span>'
    );
  }

  function escapeHtml(text) {
    const map = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
    return String(text).replace(/[&<>"']/g, c => map[c]);
  }

  /* ---- 主入口 ---- */
  async function show({ detectedEntities, desensitizedContent, originalContent }) {
    // 确保样式只注入一次
    injectStyles();

    const entities = detectedEntities || [];
    const desensitized = desensitizedContent || "";
    const original = originalContent || desensitized;

    return new Promise((resolve) => {
      const overlay = build(entities, desensitized, original);
      document.body.appendChild(overlay);

      const modal = overlay.querySelector(".ai-guard-modal");
      const btnSend = overlay.querySelector("#aiGuardBtnSend");
      const btnOriginal = overlay.querySelector("#aiGuardBtnOriginal");
      const btnCancel = overlay.querySelector("#aiGuardBtnCancel");
      const toggle = overlay.querySelector("#aiGuardToggle");
      const originalBox = overlay.querySelector("#aiGuardOriginal");

      let resolved = false;
      function finish(value) {
        if (resolved) return;
        resolved = true;
        overlay.remove();
        resolve(value);
      }

      // 按钮事件
      btnSend.addEventListener("click", () => finish("send"));
      btnOriginal.addEventListener("click", () => finish("send-original"));
      btnCancel.addEventListener("click", () => finish("cancel"));

      // 点遮罩层取消
      overlay.addEventListener("click", (e) => {
        if (e.target === overlay) finish("cancel");
      });

      // 键盘
      modal.addEventListener("keydown", (e) => {
        if (e.key === "Escape") { finish("cancel"); e.preventDefault(); }
        if (e.key === "Enter" && !e.shiftKey) { finish("send"); e.preventDefault(); }
      });

      // 原文展开/折叠
      let expanded = false;
      toggle.addEventListener("click", () => {
        expanded = !expanded;
        originalBox.classList.toggle("show", expanded);
        toggle.querySelector(".ai-guard-toggle-arrow").innerHTML = expanded ? "&#9660;" : "&#9654;";
      });

      // 自动聚焦到弹窗以启用键盘
      setTimeout(() => modal.focus(), 50);
    });
  }

  /* ---- 注入样式（幂等） ---- */
  let stylesInjected = false;
  function injectStyles() {
    if (stylesInjected) return;
    if (document.getElementById("ai-guard-popup-styles")) return;
    const style = document.createElement("style");
    style.id = "ai-guard-popup-styles";
    // 使用 chrome.runtime.getURL 读取 CSS 文件内容（若不可用则内联精简版）
    style.textContent = `
.ai-guard-overlay{position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,.55);z-index:2147483647;display:flex;align-items:center;justify-content:center;animation:ai-guard-fadein .15s ease}
@keyframes ai-guard-fadein{from{opacity:0}to{opacity:1}}
.ai-guard-modal{background:#1e1e2e;border:1px solid #313146;border-radius:12px;width:420px;max-width:92vw;max-height:85vh;box-shadow:0 24px 64px rgba(0,0,0,.5);display:flex;flex-direction:column;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif;color:#cdd6f4;animation:ai-guard-slidein .2s ease}
@keyframes ai-guard-slidein{from{transform:translateY(16px);opacity:0}to{transform:translateY(0);opacity:1}}
.ai-guard-header{display:flex;align-items:center;gap:10px;padding:16px 20px 12px;border-bottom:1px solid #313146}
.ai-guard-header-icon{width:20px;height:20px;flex-shrink:0;color:#f38ba8}
.ai-guard-header-title{font-size:15px;font-weight:600;color:#f38ba8}
.ai-guard-body{padding:14px 20px;overflow-y:auto;flex:1}
.ai-guard-section-title{font-size:12px;font-weight:500;color:#6c7086;margin-bottom:8px;text-transform:uppercase;letter-spacing:.5px}
.ai-guard-tags{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:16px}
.ai-guard-tag{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border-radius:20px;font-size:12px;font-weight:500}
.ai-guard-tag-danger{background:rgba(243,139,168,.18);color:#f38ba8}
.ai-guard-tag-warn{background:rgba(249,226,175,.18);color:#f9e2af}
.ai-guard-tag-info{background:rgba(137,180,250,.18);color:#89b4fa}
.ai-guard-tag-person{background:rgba(203,166,247,.18);color:#cba6f7}
.ai-guard-preview-box{background:#11111b;border:1px solid #313146;border-radius:8px;padding:12px;font-size:13px;line-height:1.7;color:#a6adc8;max-height:140px;overflow-y:auto;white-space:pre-wrap;word-break:break-word}
.ai-guard-placeholder{color:#f9e2af;font-weight:500}
.ai-guard-original-toggle{display:flex;align-items:center;gap:4px;font-size:12px;color:#6c7086;cursor:pointer;margin-top:10px;user-select:none}
.ai-guard-original-toggle:hover{color:#cdd6f4}
.ai-guard-original-content{display:none;background:#11111b;border:1px solid #45475a;border-radius:8px;padding:10px 12px;margin-top:6px;font-size:12px;color:#9399b2;max-height:80px;overflow-y:auto;white-space:pre-wrap;word-break:break-word}
.ai-guard-original-content.show{display:block}
.ai-guard-footer{display:flex;gap:8px;padding:12px 20px 16px;border-top:1px solid #313146}
.ai-guard-btn{flex:1;padding:9px 0;border:none;border-radius:8px;font-size:13px;font-weight:500;cursor:pointer;transition:opacity .15s}
.ai-guard-btn:hover{opacity:.85}
.ai-guard-btn:active{transform:scale(.98)}
.ai-guard-btn-primary{background:#89b4fa;color:#1e1e2e}
.ai-guard-btn-secondary{background:#45475a;color:#cdd6f4}
.ai-guard-btn-danger{background:transparent;color:#6c7086;border:1px solid #45475a}
.ai-guard-btn-danger:hover{color:#f38ba8;border-color:#f38ba8}`;
    document.head.appendChild(style);
    stylesInjected = true;
  }

  return { show };
})();
