# 浏览器插件与本地 Ollama 脱敏流程梳理及比赛方案

> 项目：ApiSensitivities（LLM 输入输出脱敏与识别）  
> 日期：2026-08-15  
> 目标：梳理浏览器插件完整执行流程，定位插件无法对接本地 Ollama 的根因，输出本地修复方案与比赛现场替代方案

## 1. 结论先行

当前项目里，浏览器插件的脱敏链路和企业 API 网关链路是两条不同路径：

1. **浏览器插件链路**当前只会调用后端 `POST /plugin/audit-check`，后端实际走的是 `DesensitizationManager -> RegexDetectionService`，核心技术栈是**正则 + HanLP NER + 自定义规则**，**没有接入本地 Ollama**。
2. **企业 API 网关链路**才会进入 `LlmProxyService`，并在其中调用 `NlpScanner` 与 `SemanticPlaceholderStrategy`，这里才使用了**本地 Ollama Agent**做语义实体提取和自反思审计。
3. 因此，“当前浏览器插件无法对接本地 Ollama”不是一个简单的网络连通性问题，而是一个**架构链路未打通**的问题：插件链路从设计上就没有接入 `NlpScanner/Ollama`。
4. 同时，本机运行态还存在一层独立问题：**本地 Ollama 服务当前未成功启动**，`11434` 无监听，`ollama list` 输出显示启动时数据库/日志锁异常，导致即使后端要调本地 Ollama，也会失败。

基于比赛展示可靠性，建议采用以下分级方案：

- **推荐正式比赛主方案**：浏览器插件 -> 企业网关后端 -> 远程受控模型服务 / 云端 Ollama
- **推荐本地开发联调方案**：浏览器插件 -> 企业网关后端 -> 本机 Ollama
- **推荐比赛兜底方案**：浏览器插件 -> 企业网关后端 -> 正则 + NER 基础脱敏，不依赖本地 Ollama

## 1.1 本轮已落地改造

当前仓库已补齐以下可直接使用的能力：

1. `NlpScanner` 已改为读取统一配置：
   - `local.agent.enabled`
   - `local.agent.mode`
   - `local.agent.url`
   - `local.agent.health-url`
   - `local.agent.model`
2. 后端已新增 `GET /plugin/agent/status`，可直接判断：
   - Agent 是否启用
   - Agent 是否可达
   - 当前模式、模型、地址与失败原因
3. `POST /plugin/audit-check` 已接入 `PluginAgentReviewService`：
   - 先走原有正则 + HanLP NER
   - Agent 可用时补充语义实体识别
   - Agent 不可用时自动降级到原链路
4. 插件配置页“测试网关/Agent”会同时检测：
   - `/actuator/health`
   - `/plugin/agent/status`
5. 响应结构已补充：
   - `detectionMode`
   - `agentEnabled`
   - `agentAvailable`
   - `agentUsed`
   - `agentEndpoint`
   - `agentModel`
   - `agentMessage`
   - `agentSemanticEntities`

## 2. 当前系统双接入模式梳理

### 2.1 浏览器插件接入模式

当前浏览器插件主要覆盖员工在第三方 AI 网站上的输入拦截，核心代码在：

- `plugin/content.js`
- `plugin/background.js`
- `src/main/java/com/hdu/apisensitivities/controller/PluginAuditController.java`
- `src/main/java/com/hdu/apisensitivities/service/DesensitizationManager.java`

执行流程如下：

1. 用户在 DeepSeek、ChatGPT、Kimi、Gemini 等页面输入文本
2. `content.js` 拦截点击发送或 `Enter`
3. `content.js` 调用 `chrome.runtime.sendMessage({ type: "gateway-review-input" })`
4. `background.js` 组装用户身份、部门、目标平台、网关地址
5. `background.js` 向后端发送 `POST /plugin/audit-check`
6. `PluginAuditController.auditCheck()` 构造 `DesensitizationRequest`
7. `DesensitizationManager.process()` 执行解析、检测、脱敏
8. `RegexDetectionService` 进行：
   - 正则匹配
   - HanLP NER 检测
   - 自定义规则匹配
9. 后端返回：
   - `detectedEntities`
   - `desensitizedContent`
   - `riskLevel`
   - `decisionAction`
   - `maskMapping`
10. 插件弹窗给用户选择：
    - 发送脱敏版
    - 发送原文
    - 取消
11. 用户决策再回写 `POST /plugin/confirm-action`

**关键事实**：这条链路当前没有 `Ollama`。

### 2.2 企业 API 接入模式

企业 API 接入用于后端系统通过统一网关接入大模型，核心代码在：

- `src/main/java/com/hdu/apisensitivities/controller/EnterpriseGatewayController.java`
- `src/main/java/com/hdu/apisensitivities/service/gateway/EnterpriseGatewayApplicationServiceImpl.java`
- `src/main/java/com/hdu/apisensitivities/service/LlmProxyService.java`
- `src/main/java/com/hdu/apisensitivities/service/SensitiveDetection/NlpScanner.java`

执行流程如下：

1. 外部系统调用 `POST /gateway/v1/chat/completions`
2. `EnterpriseGatewayController` 解析 `messages`
3. `EnterpriseGatewayApplicationServiceImpl` 根据模型名解析 `LlmProvider`
4. 构造 `LlmRequest`
5. 调用 `LlmProxyService.processLlmRequest()`
6. `LlmProxyService` 先调 `DesensitizationManager` 做基础脱敏
7. 再调用 `NlpScanner.extractEntities()` 使用本地 Ollama 做语义实体提取
8. `SemanticPlaceholderStrategy` 进一步把人名、公司名等语义实体替换成占位符
9. `NlpScanner.checkSafety()` 再做一次自反思审计
10. 再通过 `LlmClient` 调实际模型供应商
11. 返回脱敏后的模型响应并写入审计事件

**关键事实**：本地 Ollama 只在这条链路里真正被调用。

## 3. 技术栈映射

### 3.1 当前浏览器插件链路实际使用的能力

| 技术 | 当前是否生效 | 所在位置 | 作用 |
| --- | --- | --- | --- |
| 正则表达式 | 是 | `RegexDetectionService` | 手机号、身份证、银行卡、邮箱等规则匹配 |
| HanLP NER | 是 | `NlpDetectionService -> NlpEntityDetector` | 人名、地址、机构识别 |
| 本地 Ollama Agent | 否 | 插件链路未接入 | 当前插件脱敏不走本地 Agent |
| 风险评分/策略中心 | 是 | `PluginAuditController` + `RiskScorer` | 决定拦截、脱敏发送或放行 |
| 审计回写 | 是 | `GatewayAuditRepository` | 保存检测与用户操作 |

### 3.2 当前企业 API 链路实际使用的能力

| 技术 | 当前是否生效 | 所在位置 | 作用 |
| --- | --- | --- | --- |
| 正则表达式 | 是 | `DesensitizationManager` | 基础敏感数据检测 |
| HanLP NER | 是 | `RegexDetectionService` | 基础实体识别 |
| 本地 Ollama Agent | 是 | `NlpScanner` | 语义实体识别、自反思审计 |
| 多供应商代理 | 是 | `LlmProxyService` | DeepSeek/OpenAI/Ollama 等 |

## 4. 当前浏览器插件无法对接本地 Ollama 的根因定位

## 4.1 根因一：插件链路架构上未接入 Ollama

当前插件端只会调用：

- `background.js -> /plugin/audit-check`

而 `/plugin/audit-check` 内部只调用：

- `PluginAuditController -> DesensitizationManager -> RegexDetectionService`

`RegexDetectionService` 只集成了：

- 正则匹配
- HanLP NER
- 自定义规则

它**没有调用 `NlpScanner`**，因此浏览器插件不可能触发本地 Ollama Agent。

这意味着：

1. 插件无法直接获得 Ollama 的语义增强检测
2. 插件当前展示的“深度检测”本质上是后端规则检测，不是本地 Agent 检测
3. 若比赛答辩口径说“插件已接本地 Ollama”，会和当前代码事实不一致

## 4.2 根因二：插件配置面板没有 Ollama 相关配置

当前 `plugin/config.js` 只配置：

- 网关地址
- 工号/用户名
- 部门

没有任何以下配置：

- 本地 Ollama URL
- Ollama 模型名
- Agent 模式开关
- 本地 Agent 连通性测试

因此即使后续要做“插件直连本地 Ollama”或“插件联动后端本地 Agent”，前端也没有配置入口和状态感知能力。

## 4.3 根因三：本机 Ollama 当前未成功启动

本次运行态核查结果：

1. `http://127.0.0.1:11434/api/tags` 当前无法访问
2. `11434` 端口当前无监听
3. `ollama list` 输出显示：
   - `ollama server not responding - timed out waiting for server to start`
   - `disk I/O error: The segment is already unlocked`
   - 日志轮转 `Access is denied`

说明当前不是“插件连不上一个正常运行的本地 Ollama”，而是：

- 本地 `Ollama` 安装存在
- 但服务启动失败
- 后端如果此时调用 `NlpScanner`，也会失败并降级

## 4.4 根因四：Ollama 配置存在实现不一致

当前仓库里至少存在三套不一致配置：

### A. `NlpScanner`

- 固定请求地址：`http://localhost:11434/api/generate`
- 模型名：`${LOCAL_AGENT_MODEL_NAME:qwen:1.8b}`

### B. `application.properties`

- `llm.providers.ollama.url=http://localhost:11434/v1/chat/completions`
- `llm.providers.ollama.model=deepseek-r1:1.5b`

### C. `LlmConfigService`

- 从 `llm.providers.ollama.*` 读取配置
- 用于 `LlmProxyService -> OllamaClient`

这会带来两个问题：

1. **插件链路若后续接入 `NlpScanner`，会走 `/api/generate`**
2. **企业 API 若走 `OllamaClient`，会走 `/v1/chat/completions`**

也就是说，当前项目里的“本地 Ollama”并不是统一接入，而是两个不完全一致的调用口径。

## 4.5 根因五：`OllamaClient` 的继承实现不完整

`OllamaClient` 当前继承 `DeepSeekClient`，但只新增了 `createOllamaHeaders()`，没有真正覆盖父类发送逻辑。

这意味着：

1. 当前 `OllamaClient` 实际仍大量复用 DeepSeek 的请求体结构
2. 如果后续切换到 Ollama OpenAI 兼容接口，虽然大概率可用，但实现上不够清晰
3. 对比赛展示而言，一旦出现模型切换或接口兼容差异，排障成本较高

## 5. 本地 Ollama 稳定接入的可落地修复方案

## 5.1 推荐修复目标

不建议直接把浏览器插件改成“直连本地 Ollama”。  
**推荐目标是：**

`浏览器插件 -> 企业网关后端 -> 本地 Ollama Agent`

理由：

1. 插件侧继续保持简单，只处理拦截、弹窗和身份信息
2. 所有检测、审计、策略决策仍统一在后端
3. 不破坏当前企业 API / 浏览器插件双接入架构
4. 比赛现场可平滑切换到云端 Agent 或兜底模式

## 5.2 推荐改造后的目标链路

建议新增“插件 Agent 增强检测”阶段：

1. 插件仍调用 `POST /plugin/audit-check`
2. 后端先执行：
   - 正则检测
   - HanLP NER
3. 若命中以下任一条件，再进入本地 Agent 增强检测：
   - 规则检测为空但文本疑似含语义敏感信息
   - 命中 `PERSON/ORGANIZATION/ADDRESS` 等语义类实体
   - 比赛演示时显式开启“Agent 增强模式”
4. 后端新增 `LocalAgentSensitiveReviewService`
5. 由该服务统一调用本地 Ollama：
   - 补充语义实体
   - 校验脱敏是否彻底
   - 输出最终风险标签
6. 插件仍接收统一响应结构，不需要改协议

## 5.3 后端改造建议

### 必做改造

1. 新增统一配置项：
   - `local.agent.url`
   - `local.agent.model`
   - `local.agent.timeoutMs`
   - `local.agent.enabled`
2. `NlpScanner` 去掉硬编码 `OLLAMA_URL`
3. `NlpScanner` 模型名改为统一读取配置，不再单独使用 `LOCAL_AGENT_MODEL_NAME:qwen:1.8b`
4. 新增 `LocalAgentHealthService`
5. 新增接口：
   - `GET /plugin/agent/status`
   - 返回 `enabled / reachable / model / errorMessage`
6. `PluginAuditController` 增加 Agent 增强开关
7. 当本地 Agent 不可用时，自动降级到“正则 + HanLP NER”基础链路，不阻断插件使用

### 建议改造

1. `OllamaClient` 独立实现，不再继承 `DeepSeekClient`
2. 插件配置页增加“Agent 状态检测”按钮
3. 后端日志里明确区分：
   - `DETECTION_MODE=RULE_ONLY`
   - `DETECTION_MODE=RULE_NER`
   - `DETECTION_MODE=RULE_NER_OLLAMA`

## 5.4 本机 Ollama 环境修复步骤

基于本机当前日志，建议按以下顺序排障：

### 第一步：确认进程状态

检查是否已有残留 `ollama.exe` 进程、桌面 UI 进程或守护进程占用了内部数据库/日志文件。

建议操作：

1. 关闭 Ollama 桌面端
2. 结束所有 `ollama.exe` 相关进程
3. 再用命令行执行 `ollama serve`
4. 验证 `http://127.0.0.1:11434/api/tags`

### 第二步：检查日志目录权限

当前日志提示多次出现：

- `Access is denied`

说明 `C:\Users\yyy\AppData\Local\Ollama` 目录下可能存在：

1. 文件被其他进程占用
2. 当前用户对部分文件没有正常覆盖权限
3. 日志轮转异常导致启动流程卡住

建议：

1. 备份 `C:\Users\yyy\AppData\Local\Ollama`
2. 清理异常日志文件和锁文件
3. 再重启 Ollama

### 第三步：检查模型目录与模型名

当前环境变量中有：

- `OLLAMA_MODELS=E:\AI\Model`

这说明模型目录已重定向，但仍需确认：

1. `E:\AI\Model` 目录存在且可读写
2. 对应模型已真正拉取完成
3. 模型名和代码中使用的模型名一致

建议统一使用一个模型名，例如：

- `deepseek-r1:1.5b`

避免以下不一致：

- `qwen:1.8b`
- `deepseek-r1:7b`
- `deepseek-r1:1.5b`

### 第四步：检查监听地址与网络拓扑

如果后端和插件运行在同一台 Windows 主机上，使用：

- `127.0.0.1:11434`

即可。

但如果后端运行在：

- Docker
- WSL
- 远程服务器

则 `localhost` 会失效，需要改为：

- `host.docker.internal`
- 宿主机局域网 IP
- 或显式设置 `OLLAMA_HOST=0.0.0.0:11434`

### 第五步：检查 Windows 防火墙

若需要跨设备或容器访问本地 Ollama，需要放通：

- TCP `11434`

否则只能本机访问。

## 6. 比赛场景下的替代方案设计

比赛场景约束是：

1. 设备通常不是自己长期维护的开发机
2. 不一定允许安装 Ollama
3. 不一定有大模型和模型文件的本地缓存
4. 演示时最重要的是稳定，不是“本地部署纯度”

因此必须准备替代方案。

## 6.1 方案 A：云端部署 Ollama + 企业网关统一转发

### 方案描述

将 Ollama 部署在云端 Linux 主机或比赛前准备好的远程机器上，浏览器插件仍只访问企业网关，企业网关再调用远程 Ollama。

### 优点

1. 最接近“完整 Agent 增强链路”
2. 插件侧零改动
3. 答辩时可保留“本地 Agent 架构设计”，同时展示远程可运行版本
4. 更适合演示“浏览器插件 + 企业 API”统一后端

### 缺点

1. 需要公网/局域网可访问服务
2. 需要提前准备 Linux 机器、显存/内存和模型文件
3. 网络波动会直接影响演示流畅度

### 成本评估

| 项目 | 成本 |
| --- | --- |
| 云主机 | 中 |
| 部署复杂度 | 中 |
| 演示稳定性 | 中高 |
| 适配比赛 | 高 |

### 适用结论

**推荐作为比赛主方案。**

## 6.2 方案 B：轻量化远程模型服务（OpenAI 兼容）

### 方案描述

不强依赖 Ollama，改为部署一个轻量化远程模型服务，统一暴露 OpenAI 兼容接口，由后端包装成“本地 Agent 语义检测服务”。

可选实现：

1. vLLM
2. one-api / LiteLLM 代理
3. 自建小模型微服务

### 优点

1. 接口兼容性更强
2. 更容易和现有 `LlmProxyService` 融合
3. 部署模型和接口更灵活

### 缺点

1. “本地 Ollama”口径会弱化
2. 如果比赛强调端侧或本地推理，需要解释为“比赛环境下的远程 Agent 替代部署”

### 成本评估

| 项目 | 成本 |
| --- | --- |
| 云主机 | 中 |
| 部署复杂度 | 中 |
| 演示稳定性 | 高 |
| 适配比赛 | 高 |

### 适用结论

**推荐作为比赛备选主方案。**

## 6.3 方案 C：演示版基础脱敏方案（不依赖 Ollama）

### 方案描述

比赛现场完全不启用本地/远程 Agent，只使用：

- 正则匹配
- HanLP NER
- 风险策略
- 审计回写
- 一键复原

也就是直接使用当前已经跑通的插件链路。

### 优点

1. 与当前代码最一致
2. 部署最简单
3. 成功率最高
4. 不依赖本地模型、不依赖远程大模型

### 缺点

1. 少了“本地 Agent 增强”这部分亮点
2. 对语义型敏感信息的展示能力不如 Agent 模式

### 成本评估

| 项目 | 成本 |
| --- | --- |
| 部署成本 | 低 |
| 演示稳定性 | 最高 |
| 技术亮点 | 中 |
| 适配比赛 | 高 |

### 适用结论

**推荐作为比赛现场兜底方案。**

## 6.4 推荐的比赛现场最终方案

建议准备“三层预案”：

### 主演示方案

`浏览器插件 -> 企业网关 -> 远程 Agent 服务（云端 Ollama / 轻量模型服务）`

用于展示：

1. 插件拦截
2. 深度语义识别
3. 风险判定
4. 脱敏发送
5. 审计回写

### 备选方案

`企业 API -> 网关 -> 远程 / 本地 Ollama`

用于展示：

1. 统一网关代理
2. 企业后端系统接入
3. 本地 Agent 语义脱敏能力

### 兜底方案

`浏览器插件 -> 企业网关 -> 正则 + HanLP NER`

即使没有任何 Ollama 环境，也能完整演示：

1. 输入拦截
2. 敏感实体识别
3. 脱敏预览
4. 发送脱敏版 / 原文 / 取消
5. 审计留痕
6. 一键复原

## 7. 推荐的比赛演示脚本

## 7.1 演示前准备

1. 启动后端网关
2. 启动远程 Agent 服务或确认云端 Agent 可访问
3. 配置插件网关地址
4. 准备三类演示文本：
   - 规则型：手机号、身份证、邮箱
   - 语义型：客户姓名、公司名、项目名
   - 高风险型：密码、API Key、银行卡

## 7.2 建议演示顺序

### 第一段：基础链路

演示插件拦截、弹窗、发送脱敏版、审计记录。

### 第二段：Agent 增强

演示语义型实体识别，例如：

- 客户姓名
- 公司名
- 项目代号

强调这是规则和 NER 不容易完全覆盖的部分。

### 第三段：兜底能力

演示断开 Agent 后，系统仍然可以完成基础脱敏和审计，不会因为本地模型不可用导致功能失效。

## 8. 测试验证要求

## 8.1 单元测试

应覆盖：

1. `NlpScanner` 配置读取与降级逻辑
2. `LocalAgentSensitiveReviewService` 的：
   - 成功返回
   - 超时
   - 连接拒绝
   - 返回空结果
3. `PluginAuditController` 在开启/关闭 Agent 模式下的响应契约

## 8.2 集成测试

应覆盖：

1. `POST /plugin/audit-check`
2. `POST /plugin/confirm-action`
3. `GET /plugin/agent/status`
4. Agent 正常 / 异常 / 降级三种模式

## 8.3 E2E 测试

当前已有：

- `plugin/tests/extension.spec.js`
- `plugin/tests/mock-server.js`

建议补充：

1. 插件接后端真实服务的 E2E
2. 网关配置错误时的提示
3. Agent 不可用时自动降级
4. 发送脱敏内容后 AI 回复的一键复原流程

## 8.4 运行态验证

比赛前必须逐项确认：

1. `http://网关地址/actuator/health` 正常
2. `http://Agent地址/api/tags` 或兼容健康接口正常
3. 插件配置页网关测试通过
4. 至少执行一轮完整演示脚本
5. 浏览器重启后配置仍然有效
6. 无本地 Ollama 时，兜底方案仍可跑通

## 8.5 建议验收清单

- [ ] 插件发送前拦截正常
- [ ] 后端 `audit-check` 正常返回
- [ ] 规则型敏感信息可识别
- [ ] NER 型实体可识别
- [ ] Agent 模式下语义实体增强生效
- [ ] Agent 不可用时自动降级
- [ ] 用户可选择发送脱敏版 / 原文 / 取消
- [ ] `confirm-action` 回写正常
- [ ] 审计事件可查询
- [ ] 一键复原可展示完整原始内容
- [ ] 比赛机器无 Ollama 时，兜底方案仍可完整演示

## 9. 本轮排查的明确结论

### 可以确认的事实

1. 浏览器插件当前脱敏链路不走本地 Ollama
2. 企业 API 网关链路当前才会走本地 Ollama
3. 本机 Ollama 当前安装存在，但服务未成功启动
4. 当前项目对 Ollama 的配置口径不统一
5. 比赛现场不能把“本地 Ollama 必须可用”作为唯一依赖

### 最终建议

1. **短期（比赛前）**：以“浏览器插件 + 企业网关 + 远程 Agent / 兜底基础脱敏”作为正式方案
2. **中期（赛后完善）**：把 `/plugin/audit-check` 正式接入本地/远程 Agent 增强检测
3. **实现原则**：插件不直连模型，统一经后端网关转发，保留审计、策略、降级与可替换性

---

## 10. 相关代码定位

- 插件内容脚本：`plugin/content.js`
- 插件后台脚本：`plugin/background.js`
- 插件配置页：`plugin/config.js`
- 插件清单：`plugin/manifest.json`
- 插件检测入口：`src/main/java/com/hdu/apisensitivities/controller/PluginAuditController.java`
- 基础脱敏总入口：`src/main/java/com/hdu/apisensitivities/service/DesensitizationManager.java`
- 规则/NER 检测：`src/main/java/com/hdu/apisensitivities/service/SensitiveDetection/RegexDetectionService.java`
- 本地 Agent 扫描：`src/main/java/com/hdu/apisensitivities/service/SensitiveDetection/NlpScanner.java`
- 企业 API 网关：`src/main/java/com/hdu/apisensitivities/controller/EnterpriseGatewayController.java`
- LLM 代理：`src/main/java/com/hdu/apisensitivities/service/LlmProxyService.java`
- Ollama 客户端：`src/main/java/com/hdu/apisensitivities/service/LlmClient/OllamaClient.java`
