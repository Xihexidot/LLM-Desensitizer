const STORAGE_KEY_GATEWAY = "ai-guard-gateway";
const STORAGE_KEY_DEPT = "ai-guard-dept";
const STORAGE_KEY_USER_NAME = "ai-guard-user-name";

const gatewayInput = document.getElementById("gateway");
const userNameInput = document.getElementById("userName");
const deptInput = document.getElementById("dept");
const statusEl = document.getElementById("status");

async function init() {
  // 检测企业是否已通过 MDM/Group Policy 推送身份
  let managedUser = false;
  let managedDept = false;
  try {
    const managed = await chrome.storage.managed.get(["userId", "department"]);
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
  } catch (_) {
    /* managed storage 在非企业环境不可用 */
  }

  const result = await chrome.storage.local.get([
    STORAGE_KEY_GATEWAY,
    STORAGE_KEY_DEPT,
    STORAGE_KEY_USER_NAME,
  ]);
  gatewayInput.value = result[STORAGE_KEY_GATEWAY] || "";
  if (!managedUser) userNameInput.value = result[STORAGE_KEY_USER_NAME] || "";
  if (!managedDept) deptInput.value = result[STORAGE_KEY_DEPT] || "";

  if (managedUser || managedDept) {
    statusEl.textContent = "身份信息由企业 IT 统一管理";
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
  try {
    const res = await fetch(`http://${gateway}/actuator/health`, {
      method: "GET",
    });
    if (res.ok) {
      statusEl.textContent = `连接成功 (gateway: ${gateway})`;
      statusEl.className = "status ok";
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

init();
