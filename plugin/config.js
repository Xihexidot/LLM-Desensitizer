const STORAGE_KEY_GATEWAY = "ai-guard-gateway";
const STORAGE_KEY_DEPT = "ai-guard-dept";
const STORAGE_KEY_USER_NAME = "ai-guard-user-name";

const gatewayInput = document.getElementById("gateway");
const userNameInput = document.getElementById("userName");
const deptInput = document.getElementById("dept");
const statusEl = document.getElementById("status");

async function init() {
  // 检测企业是否已通过 MDM/Group Policy 推送身份与网关（只读，员工不可改）
  let managedUser = false;
  let managedDept = false;
  let managedGateway = false;
  try {
    const managed = await chrome.storage.managed.get([
      "userId",
      "department",
      "gatewayUrl",
    ]);
    if (managed.userId) {
      userNameInput.value = managed.userId;
      userNameInput.disabled = true;
      userNameInput.title = "已由企业 IT 统一管理，不可修改";
      managedUser = true;
    }
    if (managed.department) {
      deptInput.value = managed.department;
      deptInput.disabled = true;
      deptInput.title = "已由企业 IT 统一管理，不可修改";
      managedDept = true;
    }
    if (managed.gatewayUrl) {
      gatewayInput.value = managed.gatewayUrl;
      gatewayInput.disabled = true;
      gatewayInput.title = "已由企业 IT 统一管理，不可修改";
      managedGateway = true;
    }
  } catch (_) {
    /* managed storage 在非企业环境不可用 */
  }

  const result = await chrome.storage.local.get([
    STORAGE_KEY_GATEWAY,
    STORAGE_KEY_DEPT,
    STORAGE_KEY_USER_NAME,
  ]);
  if (!managedGateway) gatewayInput.value = result[STORAGE_KEY_GATEWAY] || "";
  if (!managedUser) userNameInput.value = result[STORAGE_KEY_USER_NAME] || "";
  if (!managedDept) deptInput.value = result[STORAGE_KEY_DEPT] || "";

  if (managedUser || managedDept || managedGateway) {
    const parts = [];
    if (managedGateway) parts.push("网关");
    if (managedUser) parts.push("工号");
    if (managedDept) parts.push("部门");
    statusEl.textContent = `${parts.join("、")}已由企业 IT 统一管理`;
    statusEl.className = "status ok";
  }
}

async function save() {
  const gateway = gatewayInput.value.trim();
  if (!gateway) {
    statusEl.textContent = "请输入网关地址";
    statusEl.className = "status err";
    return;
  }
  await chrome.storage.local.set({
    [STORAGE_KEY_GATEWAY]: gateway,
    [STORAGE_KEY_USER_NAME]: userNameInput.value.trim(),
    [STORAGE_KEY_DEPT]: deptInput.value.trim(),
  });
  statusEl.textContent = "已保存";
  statusEl.className = "status ok";
  setTimeout(() => {
    statusEl.textContent = "";
  }, 1500);
}

async function testConnection() {
  const gateway = gatewayInput.value.trim();
  if (!gateway) {
    statusEl.textContent = "请先输入网关地址";
    statusEl.className = "status err";
    return;
  }
  statusEl.textContent = "测试中...";
  statusEl.className = "status";
  // 规范化 URL：若用户已输入协议头则直接使用，否则补 http://
  let baseUrl = gateway;
  if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
    baseUrl = "http://" + baseUrl;
  }
  try {
    const res = await fetch(`${baseUrl}/actuator/health`, {
      method: "GET",
    });
    if (res.ok) {
      let agentMessage = "Agent 状态未知";
      let statusClass = "status ok";
      try {
        const agentRes = await fetch(`${baseUrl}/plugin/agent/status`, {
          method: "GET",
        });
        if (agentRes.ok) {
          const agent = await agentRes.json();
          if (agent.reachable) {
            agentMessage = `Agent 已就绪（${agent.mode || "OLLAMA"} / ${agent.model || "unknown"}）`;
          } else if (agent.enabled) {
            agentMessage = `Agent 不可用，当前将自动降级到正则 + NER（${agent.message || "未就绪"}）`;
            statusClass = "status";
          } else {
            agentMessage = "Agent 增强未启用，当前走正则 + NER";
            statusClass = "status";
          }
        } else {
          agentMessage = `Agent 状态接口返回 ${agentRes.status}`;
          statusClass = "status";
        }
      } catch (agentError) {
        agentMessage = `Agent 状态检查失败: ${agentError.message}`;
        statusClass = "status";
      }

      statusEl.textContent = `网关连接成功 (${baseUrl})；${agentMessage}`;
      statusEl.className = statusClass;
    } else {
      statusEl.textContent = `服务器返回 ${res.status}`;
      statusEl.className = "status err";
    }
  } catch (e) {
    statusEl.textContent = `连接失败: ${e.message}`;
    statusEl.className = "status err";
  }
}

document.getElementById("btnSave").addEventListener("click", save);
document.getElementById("btnTest").addEventListener("click", testConnection);

// ====== 剪贴板一键复原：将脱敏标记还原为发送前的原始数据 ======
const MASK_MAPPING_KEY = "ai-guard-last-mask-mapping";
const restoreInput = document.getElementById("restoreInput");
const btnRestore = document.getElementById("btnRestore");
const btnCopyResult = document.getElementById("btnCopyResult");
const restoreResult = document.getElementById("restoreResult");
const restoreStatus = document.getElementById("restoreStatus");
let lastRestoredText = "";

function loadMaskMapping() {
  return new Promise((resolve) => {
    chrome.storage.session
      .get(MASK_MAPPING_KEY)
      .then((r) => resolve(r[MASK_MAPPING_KEY] || {}))
      .catch(() => resolve({}));
  });
}

async function restoreFromClipboard() {
  const text = restoreInput.value.trim();
  if (!text) {
    restoreStatus.textContent = "请先粘贴脱敏文本";
    restoreStatus.className = "status err";
    return;
  }
  const mapping = await loadMaskMapping();
  if (!Object.keys(mapping).length) {
    restoreStatus.textContent =
      "未找到脱敏映射：请先在同一浏览器会话中发送过脱敏内容（含敏感检测的 AI 消息）";
    restoreStatus.className = "status err";
    return;
  }
  const decoded = AIGuardDecode.decodeWithHighlights(text, mapping);
  lastRestoredText = decoded.text;
  restoreResult.innerHTML = decoded.html; // decodeWithHighlights 已转义全部动态内容，仅保留高亮 <mark>
  restoreResult.hidden = false;
  btnCopyResult.disabled = false;
  restoreStatus.textContent =
    decoded.replacedCount > 0
      ? `已还原 ${decoded.replacedCount} 处脱敏标记`
      : "未发现可还原的脱敏标记（文本不含映射中的占位符）";
  restoreStatus.className = decoded.replacedCount > 0 ? "status ok" : "status";
}

function copyRestored() {
  if (!lastRestoredText) return;
  const done = () => {
    btnCopyResult.textContent = "已复制";
    setTimeout(() => (btnCopyResult.textContent = "复制原文"), 1200);
  };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard
      .writeText(lastRestoredText)
      .then(done)
      .catch(() => fallbackCopy(done));
  } else {
    fallbackCopy(done);
  }
}

function fallbackCopy(done) {
  try {
    const ta = document.createElement("textarea");
    ta.value = lastRestoredText;
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

btnRestore.addEventListener("click", restoreFromClipboard);
btnCopyResult.addEventListener("click", copyRestored);

init();
