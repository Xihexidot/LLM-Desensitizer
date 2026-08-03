# Agent 全链路数据处理流程设计文档（分级分类脱敏 + 双重校验）

> 版本：v1.0 ｜ 日期：2026-08-03 ｜ 状态：已落地实现并通过验证
> 关联代码：`service/Desensitization/GradedDesensitizationStrategy`、`SensitiveDataClassifier`、`DesensitizationVerifier`、`config/DesensitizationRuleProperties`、`service/DesensitizationManager`

## 1. 背景与问题分析

### 1.1 现状问题

现有数据脱敏效果未达业务标准，根因在于流程缺乏系统化设计：

| # | 问题 | 具体表现 | 影响 |
|---|------|----------|------|
| P1 | 单策略一刀切 | 无论敏感类型一律走 `MaskDesensitizationStrategy` 完全掩码 | 手机号/邮箱等低危信息过度脱敏，丢失业务可用性；地址/机构等高危识别弱类型无差异化处理 |
| P2 | 无分级分类 | 未区分"唯一身份标识（身份证/银行卡）"与"属性信息（地址/IP）"的危害程度 | 低危信息占用过高处理成本，高危信息防护强度不足 |
| P3 | 无校验闭环 | 脱敏结果不复查，"是否脱干净"无人问津 | 明文残留静默流出，脱敏效果无法度量 |
| P4 | 规则硬编码 | 类型→策略映射写死在代码里，业务调整需改代码发版 | 响应慢、风险高 |
| P5 | 无人工兜底 | 算法误判/漏判无人工裁决通道 | 高风险事件无法复核 |

### 1.2 设计目标

1. **标准化识别**：统一敏感类型枚举（17 类），单一识别入口；
2. **分级分类脱敏**：按危害程度分级（HIGH/MEDIUM/LOW），不同级别执行不同脱敏强度；
3. **规则动态配置**：类型→级别、校验开关、阈值等全部可配置化，无需改代码；
4. **触发时机明确**：Agent 全链路各节点明确脱敏触发点；
5. **人工 + 算法双重校验**：算法二次扫描 + 人工复核队列闭环。

---

## 2. 全链路数据流设计

```
                    ┌────────────────────────────────────────────────────────────┐
                    │                    Agent 全链路（重构后）                       │
                    └────────────────────────────────────────────────────────────┘
 用户输入 ──► ① 数据采集     ──► ② 敏感信息识别    ──► ③ 分级分类        ──► ④ 分级脱敏
 (浏览器/  │  content.js 拦截│  RegexDetectionService │  SensitiveDataClassifier │  GradedDesensitizationStrategy
  插件/API)│  统一入库       │  + HanLP NER          │  类型→级别(HIGH/MED/LOW)  │  HIGH掩码/MED部分/LOW泛化
           ▼                 ▼                       ▼                           ▼
        ⑤ 双重校验 ──► 通过 ──► ⑥ 云端 LLM 调用（仅密文/占位符出网）
         DesensitizationVerifier │
           ├─ 校验1: 明文残留扫描 │
           ├─ 校验2: 整体二次扫描 │
           └─ 未通过 ──► ⑧ 人工复核队列（ManualReviewTask，管理员裁决）
                                  │
                                  ▼
 ⑦ 输出还原 ──► ⑨ 结果审计入库（audit 表，留痕）
```

### 2.1 各环节职责与触发时机

| 环节 | 模块 | 触发时机 | 说明 |
|------|------|----------|------|
| ① 数据采集 | 插件 `content.js` / Controller | 用户提交输入、Agent 收到用户消息时 | 原始数据统一进入待处理池，不得直接出网 |
| ② 敏感信息识别 | `TextSensitiveDetectionService` | 任何内容进入 Agent 处理链之前（**前置识别**） | 正则四步走 + HanLP 实体识别，输出实体列表 |
| ③ 分级分类 | `SensitiveDataClassifier` | 识别完成后立即执行 | 类型→级别映射，支持动态规则覆盖 |
| ④ 分级脱敏 | `GradedDesensitizationStrategy` | 识别+分级完成后、内容发往云端前（**强制脱敏点**） | HIGH 完全掩码 / MEDIUM 部分脱敏 / LOW 泛化；会话一致性占位符 |
| ⑤ 双重校验 | `DesensitizationVerifier` | 脱敏完成后立即执行（**校验点**） | 算法校验 + 人工复核队列 |
| ⑥ 云端调用 | `LlmProxyService` | 校验通过后 | 仅密文/占位符出网；自反思等中间产物同样先脱敏后入模 |
| ⑦ 输出还原 | `SemanticPlaceholderStrategy` / 响应组装 | LLM 返回后、回传用户前 | 依据会话占位符映射表还原，防止中间态泄漏 |
| ⑧ 人工复核 | `DesensitizationVerifier.ManualReviewTask` | 校验未通过时异步触发 | 管理员人工裁决（PENDING→RESOLVED/DISMISSED） |
| ⑨ 结果审计 | 审计表 / 日志 | 全链路各节点 | 脱敏前后对照、校验结论留痕 |

### 2.2 多场景触发矩阵

| 场景 | 触发时机 | 处理方式 |
|------|----------|----------|
| 浏览器插件拦截 | `reviewInput` 发起 `audit-check` 前 | 先本地分级脱敏再上报网关 |
| 纯文本（Agent 对话输入） | 进入处理链时 | 完整识别→分级→脱敏→校验 |
| 结构化数据（JSON/XML） | 解析后、入模前 | 字段路径/值级替换，分级策略统一适用 |
| 二进制（图片/PDF） | 解析为文本后 | 文本部分分级脱敏，二进制本体跳过并记录 |
| LLM 自反思/中间产物 | 每次回灌模型前 | 对中间产物重新执行识别+脱敏（防二次泄露） |
| 输出还原 | 模型返回后 | 会话占位符映射还原，还原结果不再次脱敏 |

---

## 3. 模块设计

### 3.1 敏感信息分级标准（SensitiveLevel）

依据**危害程度与可恢复性**三级划分：

| 级别 | 处理策略 | 覆盖类型 | 保留信息 |
|------|----------|----------|----------|
| HIGH（高敏） | 完全掩码 `[ID_CARD_1]` | 身份证号、银行卡号、信用卡、护照、社保号、密码、API Key | 无（零信息残留） |
| MEDIUM（中敏） | 部分脱敏 `[138****8000]` | 手机号、邮箱、人名、出生日期 | 前3后4、邮箱前缀2位、姓氏 |
| LOW（低敏） | 泛化处理 `[浙江省地区]` | 地址、机构、IP、车牌、自定义 | 省级/号段/年龄区间等统计价值 |

> 分级依据：HIGH 类一旦泄露无法恢复且可直接用于身份冒用；MEDIUM 类泄露后可关联到具体个人；LOW 类泄露风险相对可控且需保留业务统计价值。

### 3.2 分级分类器（SensitiveDataClassifier）

- 默认映射固化于 `buildDefaultLevels()`（17 类全覆盖）；
- 动态覆盖：`desensitization.rule.type-levels[类型]=级别`（无需改代码）；
- 兜底：未知/空类型归为 LOW。

### 3.3 分级脱敏策略（GradedDesensitizationStrategy）

- 实现 `DesensitizationStrategy` 接口，支持 TEXT/JSON/XML；
- **索引安全**：所有实体按 start 倒序单遍替换，避免多次替换索引偏移；
- **会话一致性**：同一会话内同一明文始终映射到同一占位符（复用 `GlobalSessionContextRepository`）；
- **动态级别覆盖**：业务侧把某类型临时升/降级后，脱敏样式随之切换（如手机号升级 HIGH → 输出 `[PHONE_1]`）。

### 3.4 双重校验器（DesensitizationVerifier）

```
DesensitizationVerifier.verify(明文, 脱敏后文本, 实体列表, 语言)
   ├─ 算法校验①  明文残留扫描：逐个实体比对原文是否仍出现在脱敏结果
   ├─ 算法校验②  整体二次扫描：屏蔽占位符区域后，仅以高置信正则类型重新检测
   │              （PHONE_NUMBER/ID_CARD/BANK_CARD/EMAIL/IP/车牌/护照/API_KEY 等）
   ├─ 覆盖率计算  已消除明文实体数 / 检测实体总数
   └─ 判定与分流  coverage < 阈值(默认0.9) 或存在残留 → 入人工复核队列
                  否则判定通过
```

### 3.5 规则动态配置（DesensitizationRuleProperties）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `desensitization.rule.graded-enabled` | true | 分级脱敏总开关（关闭则回退旧单策略逻辑） |
| `desensitization.rule.type-levels[类型]` | 见 3.1 | 类型→级别映射，可动态调整 |
| `desensitization.rule.verify-enabled` | true | 算法校验开关 |
| `desensitization.rule.manual-review-enabled` | true | 人工复核开关 |
| `desensitization.rule.coverage-threshold` | 0.9 | 覆盖率最低阈值，低于则触发人工复核 |
| `desensitization.rule.level-strategy[级别]` | HIGH→mask / MEDIUM→partial / LOW→generalization | 级别→策略名（预留扩展） |

---

## 4. 双重校验机制详解

### 4.1 算法校验（自动化）

1. **明文残留扫描（校验①）**：对每个已识别实体，检查其 `originalText` 是否仍出现在脱敏结果中。任一残留即判未通过。这是最直接的"脱干净没有"判据。
2. **整体二次扫描（校验②）**：将脱敏结果的占位符区域（`[...]`）整体屏蔽后，仅用**高置信正则类型**重新执行检测。设计意图：
   - 捕获首轮漏识别（如带空格/连字符变体手机号）但在脱敏后仍残留的模式；
   - 规避 NLP 二次识别的假阳性（人名/地址上下文词被误判），仅保留确定性高的正则证据。

### 4.2 人工复核（人工兜底）

- **触发条件**：覆盖率低于阈值 或 任一轮算法校验发现残留；
- **载体**：`ManualReviewTask`（reviewId / 原文 / 脱敏后文本 / 残留清单 / 覆盖率 / 状态）；
- **队列**：进程内有界队列（上限 200，`DesensitizationVerifier.getPendingReviews()`）；
- **流转**：PENDING → RESOLVED（确认整改）/ DISMISSED（人工判定误报）；
- **与业务集成**：校验未通过时 Manager 记录 WARN 日志并在响应 message 中提示已进入人工复核。

### 4.3 失败降级

- 二次扫描服务不可用（NLP 异常）→ 降级为仅校验①，不阻断主流程；
- 校验开关关闭 → 返回 disabled 结果，主流程照常。

---

## 5. 代码落地清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `entity/SensitiveLevel.java` | 新增 | HIGH/MEDIUM/LOW 三级枚举 |
| `config/DesensitizationRuleProperties.java` | 新增 | 规则动态配置（@ConfigurationProperties） |
| `service/Desensitization/SensitiveDataClassifier.java` | 新增 | 类型→级别分类器（默认映射 + 动态覆盖） |
| `service/Desensitization/GradedDesensitizationStrategy.java` | 新增 | 分级脱敏策略（HIGH/MED/LOW 三种处理） |
| `service/Desensitization/DesensitizationVerifier.java` | 新增 | 算法二次扫描 + 人工复核队列 |
| `service/DesensitizationManager.java` | 修改 | 分级策略优先选择 + 脱敏后自动双重校验 |
| `application.properties` | 修改 | 动态规则配置块（类型级别/校验开关/阈值） |
| `src/test/.../desensitization/SensitiveDataClassifierTest.java` | 新增 | 分类器单元测试（6 用例） |
| `src/test/.../desensitization/GradedDesensitizationStrategyTest.java` | 新增 | 分级策略单元测试（8 用例） |
| `src/test/.../desensitization/DesensitizationVerifierTest.java` | 新增 | 校验器单元测试（8 用例） |
| `src/test/.../desensitization/GradedFlowVerificationTest.java` | 新增 | 全链路指标验证测试（真实数据集） |

验证结果详见《分级分类脱敏流程落地验证报告》。
