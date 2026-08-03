/**
 * 企业策略存储模块（chrome.storage.managed）
 *
 * 背景：企业 IT 通过 Windows 组策略(GPO) / Mac MDM / Linux policies 将员工工号、部门等
 * 固定业务属性推送至 chrome.storage.managed。该存储区域对扩展【只读】——扩展侧调用
 * set/remove/clear 会直接报错，且策略值位于操作系统级（注册表 HKLM / MDM profile），
 * 普通员工无写权限，因此具备"员工端不可篡改的强制存储"能力。
 *
 * 读取优先级：managed(企业策略，只读) > local(员工手动填写) > 自动生成 ID。
 *
 * 注意：必须同时满足以下条件，chrome.storage.managed 才会返回策略值：
 *  1. manifest.json 声明 "storage": { "managed_schema": "managed_schema.json" }；
 *  2. 企业策略已推送到本机（chrome://policy 可见，Status 为 OK）；
 *  3. 推送的键名与 managed_schema.json properties 中的键名完全一致。
 */
(function (global) {
  "use strict";

  // 必须与 managed_schema.json 的 properties 键名保持一致
  const MANAGED_SCHEMA_KEYS = [
    "userId",
    "department",
    "companyName",
    "gatewayUrl",
    "complianceMode",
  ];

  function log(...args) {
    console.log("[AI-Guard ManagedStore]", ...args);
  }

  /** 读取全部策略值；未推送 / 非 Chromium 环境返回 null */
  async function readManaged() {
    try {
      if (!chrome || !chrome.storage || !chrome.storage.managed) {
        log("chrome.storage.managed 不可用（非 Chromium 系浏览器或权限缺失）");
        return null;
      }
      const items = await chrome.storage.managed.get(null);
      if (items && Object.keys(items).length > 0) {
        return items;
      }
      return null;
    } catch (e) {
      log("读取 managed 失败:", e?.message);
      return null;
    }
  }

  /**
   * 获取企业推送的业务属性画像。
   * @returns {Promise<{source: "managed"|"none", profile: Object|null}>}
   *   source=managed：命中企业策略（只读，不可篡改）；source=none：未推送或为空。
   */
  async function getManagedProfile() {
    const managed = await readManaged();
    if (!managed) {
      return { source: "none", profile: null };
    }
    const profile = {};
    let hasAny = false;
    for (const key of MANAGED_SCHEMA_KEYS) {
      const v = managed[key];
      if (v !== undefined && v !== null && String(v).trim() !== "") {
        profile[key] = v;
        hasAny = true;
      }
    }
    if (!hasAny) {
      log("managed 区域存在但未匹配到有效策略键，请检查推送键名是否与 schema 一致");
      return { source: "none", profile: null };
    }
    log("命中企业策略推送:", Object.keys(profile).join(","));
    return { source: "managed", profile };
  }

  /** 监听企业策略变更（IT 管理员更新策略后实时感知） */
  function watch(callback) {
    if (!chrome || !chrome.storage || !chrome.storage.onChanged) return;
    chrome.storage.onChanged.addListener((changes, areaName) => {
      if (areaName === "managed") {
        log("企业策略发生变化，变更键:", Object.keys(changes));
        callback(changes);
      }
    });
  }

  global.ManagedStore = {
    getManagedProfile,
    readManaged,
    watch,
    MANAGED_SCHEMA_KEYS,
  };
})(typeof self !== "undefined" ? self : this);
