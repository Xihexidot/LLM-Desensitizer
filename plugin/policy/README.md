# AI 输入安全助手 — 企业策略推送部署指南（chrome.storage.managed）

> 目标：由企业 IT 管理员通过 **Windows 组策略(GPO) / Mac MDM / Linux policies** 将员工工号、所属部门等固定业务属性，推送至 `chrome.storage.managed`，实现**员工端不可篡改的强制存储**，并满足跨会话、跨设备长期留存与隐私合规要求。

## 一、工作原理

```
企业 IT 管理员                    员工设备                          浏览器扩展
┌──────────────┐   GPO/MDM    ┌───────────────────┐  只读  ┌──────────────────────┐
│ AD 域控制器    │ ──────────▶ │ 注册表 HKLM\...\   │ ─────▶ │ chrome.storage.managed │
│ MDM (Intune/  │   下发       │ Policies\...\policy│        │  (ManagedStore 读取)   │
│ Jamf/...)     │             │ MDM profile /      │        └──────────────────────┘
└──────────────┘              │ policies.json      │
                              └───────────────────┘
       普通员工无 HKLM\Policies 写权限 → 值不可被员工篡改
```

- `chrome.storage.managed` 对扩展**只读**：调用 `set/remove/clear` 会直接报错。
- 策略值位于操作系统级（注册表/MDM Profile），浏览器重启后仍生效 → **跨会话**。
- 域内新设备加入时 GPO/MDM 自动下发 → **跨设备**。
- 扩展侧读取是本地读取，**不上传至任何第三方**（含 Google）→ 合规基础良好。

## 二、必需的三处配置（缺一不可）

| # | 文件 | 作用 |
|---|------|------|
| 1 | `plugin/managed_schema.json` | 声明允许推送的键名、类型与枚举（无此文件则 Chrome 拒绝发布策略） |
| 2 | `plugin/manifest.json` | `"storage": { "managed_schema": "managed_schema.json" }`（已完成） |
| 3 | `plugin/policy/*` 推送模板 | 将实际策略值下发到员工设备 |

## 三、Windows GPO 部署步骤

1. 扩展 ID 已固定为 `ndlhcpcbahekidhmdcfkbmjdehiehglg`（`plugin/manifest.json` 的 `"key"` 字段固化，任何电脑加载 ID 恒定），`windows-gpo.reg` 已填好，无需手动替换。
2. **临时验证**（单机）：管理员身份运行 `reg import windows-gpo.reg`。
3. **正式发布**：在 AD 域控上配置 GPO → "计算机配置 → 管理模板"，导入 Chrome ADMX 模板，将 5 个策略值填入 `3rdparty/extensions/ndlhcpcbahekidhmdcfkbmjdehiehglg/policy` 对应项。
4. 员工端验证：`chrome://policy` → 点"重新加载策略" → 扩展策略 Status = OK。

## 四、Mac MDM 部署步骤

1. `plugin/policy/macos-mdm.plist` 已按固定 ID `ndlhcpcbahekidhmdcfkbmjdehiehglg` 填好，无需手动替换。
2. 在 MDM 控制台（Jamf / Intune / Kandji）中：
   - 上传为 `com.apple.ManagedClient.preferences` 描述文件；
   - `Data` 字段填入本 plist 的 **base64 编码**内容（MDM 要求）；
   - 作用域选择目标员工组。
3. 员工端验证：`chrome://policy` Status = OK，或终端执行 `defaults read com.google.Chrome` 查看扩展策略。

## 五、Linux policies 部署

1. `plugin/policy/linux-policies.json` 已按固定 ID `ndlhcpcbahekidhmdcfkbmjdehiehglg` 填好，无需手动替换。
2. 复制到 `/etc/opt/chrome/policies/managed/ai-guard.json`（企业级路径，普通用户不可写）。
3. 重启浏览器后 `chrome://policy` 验证。

## 六、兼容性矩阵

| 浏览器 | chrome.storage.managed | 企业策略下发通道 | 说明 |
|--------|:---:|------|------|
| Google Chrome (Windows) | ✅ | GPO/注册表（HKLM） | 支持 |
| Google Chrome (macOS) | ✅ | MDM 描述文件 | 支持 |
| Google Chrome (Linux) | ✅ | /etc/opt/chrome/policies/managed | 支持 |
| Microsoft Edge 79+ | ✅ | 注册表（HKLM\Software\Policies\Microsoft\Edge） | 支持 |
| Brave/Opera/Vivaldi | ✅ | 同 Chromium 注册表路径 | 支持 |
| Firefox 57+ | ⚠️ | Windows 注册表不支持，需 native manifest | 需单独适配 |
| Safari / iOS / Android | ❌ | 不支持 | 不可用于移动端 |

## 七、隐私合规要点（GDPR / 个人信息保护法）

1. **合法基础**：工号、部门属于员工个人信息，企业推送属于"履行劳动合同/员工管理所必需"，符合 PIPL 第 13 条与 GDPR Art.6(1)(b)。
2. **最小化**：仅推送脱敏与审计所必需的最小属性集（当前 5 个键），不采集位置、设备、浏览行为。
3. **本地处理**：策略值仅在本机浏览器读取，不随插件上报第三方；网关侧仅用于身份绑定与审计。
4. **告知**：建议在《员工隐私政策》与插件配置页说明企业属性推送事实与用途。
5. **保留期限**：员工离职时由 IT 从 GPO/MDM 作用域移除，设备重装后策略随之消失。

## 八、验证清单（MVP 验收）

- [ ] `plugin/manifest.json` 含 `storage.managed_schema` 声明
- [ ] `chrome://policy` 显示扩展策略且 Status = OK
- [ ] Service Worker 日志出现 `命中企业策略推送: userId,department,...`
- [ ] 扩展调用 `chrome.storage.managed.set()` 报错（只读验证）
- [ ] 员工在普通用户权限下无法通过注册表编辑器修改 HKLM 策略值
- [ ] 员工手动填写与策略值冲突时，**策略值优先**（背景日志 source=managed）
- [ ] 未推送策略的机器自动降级（source=none → 自动生成 ID），功能不受影响
