# 企业 AI 安全网关接口协议设计

## 1. 文档目标

这份文档定义企业 AI 安全网关的对外接口协议，用于指导后续实现、联调和接入。

目标不是做一个只适合当前 demo 的接口，而是设计一套适合企业落地的统一协议，满足以下场景：

- 浏览器插件接入
- 业务系统 API 接入
- OpenAI 兼容网关接入
- RAG 平台接入
- Agent / 工作流平台接入
- 异步文件和批处理接入

这份协议重点解决：

1. 请求如何标准化
2. 上下文如何透传
3. 风险决策如何返回
4. 如何支持在线放行、脱敏、阻断、路由和审计

## 2. 设计原则

### 2.1 网关优先

所有调用应优先经过统一网关，而不是各系统直接调用外部模型。

### 2.2 统一协议，多入口接入

虽然入口很多，但最终都应该落到统一网关协议：

- 浏览器插件
- 业务系统
- LLM SDK
- Agent 平台
- RAG 服务

### 2.3 同时支持同步和异步

文本类请求适合同步。

文件类、大文档类、批处理类请求必须支持异步。

### 2.4 审计字段内建

审计不是后补字段，必须在协议层一开始就保留：

- 调用方身份
- 应用身份
- 场景身份
- 风险动作
- 策略版本

### 2.5 决策结果可解释

网关返回的不应只是“成功/失败”，而应明确：

- 是否命中风险
- 执行了什么动作
- 为什么阻断或改路由

## 3. 接口分层

建议将企业版接口分成 4 组。

### 3.1 在线调用接口

用于实时请求：

- 文本聊天
- 结构化输入
- RAG prompt
- Agent 工具调用

### 3.2 文件与异步任务接口

用于：

- PDF / DOCX / Excel / 图片 / 音频
- 批量任务
- 大体积输入

### 3.3 审计与观测接口

用于：

- 查询审计日志
- 查询风险事件
- 查询策略命中

### 3.4 策略与规则控制接口

用于：

- 管理策略
- 管理规则
- 管理应用接入信息

## 4. 协议版本与路径规范

建议统一以版本化路径对外暴露：

```text
/gateway/v1/...
```

例如：

- `/gateway/v1/chat`
- `/gateway/v1/structured`
- `/gateway/v1/files/tasks`
- `/gateway/v1/audit/events`

如果要提供 OpenAI 兼容协议，可单独保留：

```text
/openai/v1/chat/completions
```

它本质上是兼容层，内部仍应转换为统一网关协议。

## 5. 鉴权与请求头规范

建议统一使用以下请求头。

### 5.1 必填请求头

- `Authorization: Bearer <token>`
- `X-App-Id: <appId>`
- `X-Request-Id: <requestId>`
- `X-Tenant-Id: <tenantId>`
- `X-Channel: <channel>`

### 5.2 推荐请求头

- `X-User-Id: <userId>`
- `X-User-Role: <role>`
- `X-Department: <department>`
- `X-Scene-Code: <sceneCode>`
- `X-Environment: prod | staging | test`
- `X-Trace-Id: <traceId>`

### 5.3 `X-Channel` 枚举建议

- `browser-plugin`
- `web-console`
- `backend-api`
- `agent-runtime`
- `rag-service`
- `sdk`

### 5.4 鉴权建议

企业版建议至少支持：

- 应用级 API Key
- 用户级 JWT / SSO Token
- 服务到服务的签名鉴权

推荐组合：

- 面向业务系统：`App Key + Secret / Service Token`
- 面向员工侧：`SSO Token + App Id`

## 6. 统一响应结构

所有非兼容层接口，建议统一返回以下结构：

```json
{
  "code": "GW-0000",
  "message": "success",
  "requestId": "req-20260601-001",
  "traceId": "trace-abc-123",
  "success": true,
  "data": {},
  "risk": {},
  "audit": {}
}
```

字段说明：

- `code`
  - 业务返回码
- `message`
  - 可读描述
- `requestId`
  - 客户端请求 ID
- `traceId`
  - 平台链路追踪 ID
- `success`
  - 接口调用是否成功
- `data`
  - 正常业务结果
- `risk`
  - 风险命中与决策结果
- `audit`
  - 审计摘要信息

## 7. 风险决策结果结构

建议每个在线响应都可以带回 `risk` 字段。

```json
{
  "riskLevel": "medium",
  "decisionAction": "DESENSITIZE_AND_ALLOW",
  "matchedTypes": ["MOBILE_PHONE", "ID_CARD"],
  "matchedRules": ["RULE_PHONE_CN", "RULE_ID_CARD_CN"],
  "policyId": "policy-customer-service",
  "policyVersion": "2026-06-01.1",
  "routeTarget": "INTERNAL_QWEN",
  "needApproval": false
}
```

### 7.1 `riskLevel` 枚举建议

- `none`
- `low`
- `medium`
- `high`
- `critical`

### 7.2 `decisionAction` 枚举建议

- `ALLOW`
- `DESENSITIZE_AND_ALLOW`
- `ROUTE_TO_INTERNAL_MODEL`
- `BLOCK`
- `REQUIRE_APPROVAL`
- `ASYNC_REVIEW`

## 8. 在线聊天接口

### 8.1 统一聊天接口

`POST /gateway/v1/chat`

用于：

- 文本聊天
- 浏览器插件转发
- 业务系统调用
- Agent 文本类请求

请求示例：

```json
{
  "provider": {
    "preferred": "DEEPSEEK",
    "fallback": ["QWEN_PRIVATE", "OPENAI"],
    "allowExternal": true
  },
  "requestContext": {
    "sessionId": "sess-001",
    "sceneCode": "CUSTOMER_SERVICE",
    "channel": "browser-plugin",
    "environment": "prod",
    "dataClassification": "L2",
    "isExternalModel": true
  },
  "userContext": {
    "userId": "u123",
    "userRole": "customer-service",
    "department": "support"
  },
  "input": {
    "type": "TEXT",
    "content": "客户张三的手机号是13812345678，身份证号是3301xxxx，帮我总结问题。",
    "attachments": []
  },
  "options": {
    "stream": false,
    "enableOutputReview": true,
    "enableDesensitization": true,
    "strictMode": false
  },
  "metadata": {
    "knowledgeBaseId": "kb-01",
    "templateId": "tpl-cs-summary"
  }
}
```

响应示例：

```json
{
  "code": "GW-0000",
  "message": "success",
  "requestId": "req-001",
  "traceId": "trace-001",
  "success": true,
  "data": {
    "provider": "DEEPSEEK",
    "actualRoute": "QWEN_PRIVATE",
    "originalPrompt": "客户张三的手机号是13812345678，身份证号是3301xxxx，帮我总结问题。",
    "processedPrompt": "客户[CHINESE_NAME]的手机号是[PHONE]，身份证号是[ID_CARD]，帮我总结问题。",
    "responseText": "该客户反映的问题主要集中在售后处理时效和身份核验流程。",
    "processingTimeMs": 721
  },
  "risk": {
    "riskLevel": "medium",
    "decisionAction": "ROUTE_TO_INTERNAL_MODEL",
    "matchedTypes": ["CHINESE_NAME", "MOBILE_PHONE", "ID_CARD"],
    "matchedRules": ["RULE_NAME_CN", "RULE_PHONE_CN", "RULE_ID_CARD_CN"],
    "policyId": "policy-customer-service",
    "policyVersion": "2026-06-01.1",
    "routeTarget": "QWEN_PRIVATE",
    "needApproval": false
  },
  "audit": {
    "eventId": "evt-001",
    "inputRiskLevel": "medium",
    "outputRiskLevel": "low"
  }
}
```

## 9. 结构化输入接口

### 9.1 结构化请求接口

`POST /gateway/v1/structured`

适用：

- JSON 对象
- 表单字段
- RAG 回填上下文
- 工具调用参数

请求示例：

```json
{
  "requestContext": {
    "sessionId": "sess-structured-01",
    "sceneCode": "CRM_SUMMARY",
    "channel": "backend-api",
    "dataClassification": "L3"
  },
  "input": {
    "type": "STRUCTURED",
    "structuredData": {
      "customerName": "张三",
      "phone": "13812345678",
      "address": "杭州市西湖区xxx",
      "summaryRequirement": "生成客户问题摘要"
    }
  },
  "options": {
    "enableOutputReview": true
  }
}
```

## 10. OpenAI 兼容接口

### 10.1 兼容路径

`POST /openai/v1/chat/completions`

目标：

- 兼容现有 SDK
- 减少业务系统改造成本

说明：

- 外部看起来像 OpenAI 协议
- 内部应先转为 `/gateway/v1/chat` 的统一格式

建议兼容字段：

- `model`
- `messages`
- `stream`
- `temperature`
- `top_p`
- `tools`
- `tool_choice`

建议新增的企业扩展字段可放在：

- `metadata`
- HTTP Header

例如：

```json
{
  "model": "deepseek-chat",
  "messages": [
    { "role": "user", "content": "请帮我总结客户问题，客户电话是13812345678" }
  ],
  "stream": false,
  "metadata": {
    "sceneCode": "CUSTOMER_SERVICE",
    "dataClassification": "L2"
  }
}
```

## 11. 文件异步任务接口

### 11.1 创建文件任务

`POST /gateway/v1/files/tasks`

用于：

- PDF
- DOCX
- Excel
- 图片 OCR
- 音频转写
- 大文件异步治理

请求方式建议：

- `multipart/form-data`

表单字段建议：

- `file`
- `sceneCode`
- `channel`
- `dataClassification`
- `targetModel`
- `enableOutputReview`

响应示例：

```json
{
  "code": "GW-0000",
  "message": "task accepted",
  "success": true,
  "data": {
    "taskId": "task-file-001",
    "status": "PENDING",
    "queryUrl": "/gateway/v1/files/tasks/task-file-001"
  }
}
```

### 11.2 查询文件任务

`GET /gateway/v1/files/tasks/{taskId}`

返回字段建议：

- `taskId`
- `status`
- `progress`
- `detectedTypes`
- `decisionAction`
- `resultUrl`
- `errorMessage`

### 11.3 任务状态枚举

- `PENDING`
- `PARSING`
- `DETECTING`
- `DESENSITIZING`
- `ROUTING`
- `COMPLETED`
- `FAILED`
- `BLOCKED`
- `WAITING_APPROVAL`

## 12. 批量接口

### 12.1 批量文本治理接口

`POST /gateway/v1/batch/chat`

请求示例：

```json
{
  "batchId": "batch-001",
  "items": [
    {
      "itemId": "1",
      "input": {
        "type": "TEXT",
        "content": "客户电话13812345678"
      }
    },
    {
      "itemId": "2",
      "input": {
        "type": "TEXT",
        "content": "这是公开营销文案"
      }
    }
  ]
}
```

响应建议：

- 同步小批量可直接返回结果
- 大批量建议转异步任务

## 13. 审计查询接口

### 13.1 审计事件查询

`GET /gateway/v1/audit/events`

查询参数建议：

- `tenantId`
- `appId`
- `userId`
- `sceneCode`
- `decisionAction`
- `riskLevel`
- `provider`
- `startTime`
- `endTime`
- `pageNo`
- `pageSize`

响应字段建议：

- `eventId`
- `timestamp`
- `tenantId`
- `appId`
- `userId`
- `channel`
- `targetProvider`
- `decisionAction`
- `matchedTypes`
- `policyId`
- `policyVersion`

### 13.2 单个审计事件详情

`GET /gateway/v1/audit/events/{eventId}`

返回完整审计事件信息，包括：

- 基本信息：eventId、timestamp、tenantId、appId、userId、department、channel
- 风险信息：inputRiskLevel、outputRiskLevel、decisionAction、userAction、matchedSensitiveTypes
- **内容审查**：originalContent（原始输入）、processedContent（脱敏后内容）—— 供管理员安全审查

> 注意：此接口为管理员审查使用，返回完整原文。不应暴露给普通用户。

### 13.3 审计统计接口

`GET /gateway/v1/audit/stats`

返回今日统计摘要，按多维度分组：

```json
{
  "todayTotal": 47,
  "byChannel": [
    { "channel": "BROWSER_PLUGIN", "cnt": 35 },
    { "channel": "backend-api", "cnt": 12 }
  ],
  "byRiskLevel": [
    { "input_risk_level": "HIGH", "cnt": 4 },
    { "input_risk_level": "MEDIUM", "cnt": 18 }
  ],
  "byDecision": [
    { "decision_action": "DESENSITIZE_AND_ALLOW", "cnt": 30 },
    { "decision_action": "BLOCK", "cnt": 8 }
  ],
  "byTargetProvider": [
    { "target_provider": "DeepSeek", "cnt": 35 },
    { "target_provider": "ChatGPT", "cnt": 8 }
  ],
  "byUserAction": [
    { "user_action": "DESENSITIZE_AND_SEND", "cnt": 20 },
    { "user_action": "CANCEL", "cnt": 10 }
  ]
}
```

## 14. 插件审计管道接口

### 14.1 插件发送前检查

`POST /plugin/audit-check`

用于浏览器插件在用户点击发送时，将输入内容提交检查。

请求示例：

```json
{
  "content": "客户张三，手机号13812345678",
  "dataType": "TEXT",
  "language": "zh",
  "userId": "user-m4k7x2p9",
  "department": "客服部",
  "targetProvider": "DeepSeek",
  "strictMode": false,
  "autoScenarioDetection": false
}
```

字段说明：

- `content`：用户输入原文
- `userId`：插件端自动生成的用户唯一标识（chrome.storage.local 持久化）
- `department`：可配置的部门信息
- `targetProvider`：插件根据当前网页域名自动检测的目标 LLM 平台名（DeepSeek / ChatGPT / Kimi / 通义千问 / 豆包 等）

响应示例：

```json
{
  "detectedEntities": [
    { "type": "PERSON_NAME", "value": "张三", "start": 0, "end": 2 },
    { "type": "PHONE_NUMBER", "value": "13812345678", "start": 4, "end": 15 }
  ],
  "desensitizedContent": "客户[NAME_1]，手机号[PHONE_1]",
  "auditEventId": "evt-abc123"
}
```

说明：此接口执行检测 + 脱敏 + 写入审计表，返回 `auditEventId` 供后续用户操作确认。

### 14.2 插件用户操作确认

`POST /plugin/confirm-action`

用于回写用户在弹窗中的选择。

请求示例：

```json
{
  "auditEventId": "evt-abc123",
  "userAction": "DESENSITIZE_AND_SEND"
}
```

`userAction` 枚举：

- `DESENSITIZE_AND_SEND` — 发送脱敏版
- `SEND_ORIGINAL` — 发送原文
- `CANCEL` — 取消发送

## 15. 策略与规则接口

### 14.1 策略查询接口

`GET /gateway/v1/policies`

### 15.2 策略发布接口

`POST /gateway/v1/policies/publish`

请求示例：

```json
{
  "policyId": "policy-customer-service",
  "version": "2026-06-01.2",
  "scope": {
    "appIds": ["crm-service"],
    "sceneCodes": ["CUSTOMER_SERVICE"]
  },
  "rules": [
    {
      "matchTypes": ["MOBILE_PHONE", "ID_CARD"],
      "action": "DESENSITIZE_AND_ALLOW"
    },
    {
      "matchTypes": ["BANK_CARD", "PAYMENT_PASSWORD"],
      "action": "BLOCK"
    }
  ]
}
```

### 14.3 应用接入配置接口

`POST /gateway/v1/apps/register`

用于：

- 注册应用
- 分配应用密钥
- 绑定默认策略

## 15. 错误码设计建议

建议统一使用：

```text
GW-XXXX
```

### 15.1 成功码

- `GW-0000` 成功

### 15.2 鉴权类

- `GW-1001` token 无效
- `GW-1002` appId 未注册
- `GW-1003` 租户无权限

### 15.3 请求类

- `GW-2001` 请求体不合法
- `GW-2002` 不支持的数据类型
- `GW-2003` 文件过大

### 15.4 风险决策类

- `GW-3001` 请求被阻断
- `GW-3002` 请求需审批
- `GW-3003` 请求已改路由到内部模型

### 15.5 模型调用类

- `GW-4001` 模型调用失败
- `GW-4002` 模型超时
- `GW-4003` 外部模型不可用

### 15.6 平台内部类

- `GW-5001` 策略引擎异常
- `GW-5002` 检测引擎异常
- `GW-5003` 审计写入失败

## 16. 协议落地建议

### 16.1 先实现的最小接口集合

建议先做 MVP：

1. `POST /gateway/v1/chat`
2. `POST /openai/v1/chat/completions`
3. `POST /gateway/v1/files/tasks`
4. `GET /gateway/v1/files/tasks/{taskId}`
5. `GET /gateway/v1/audit/events`
6. `GET /gateway/v1/audit/events/{eventId}` — 审计事件详情
7. `GET /gateway/v1/audit/stats` — 审计统计
8. `POST /plugin/audit-check` — 插件审计管道
9. `POST /plugin/confirm-action` — 用户操作确认

### 16.2 当前项目的改造对应

可以优先复用：

- `GatewayController`
  - 演进为兼容层入口
- `LlmProxyService`
  - 演进为网关编排核心
- `DesensitizationManager`
  - 演进为输入治理能力
- `SensitiveRuleService`
  - 演进为策略底层能力的一部分

需要新增：

- `统一网关请求对象`
- `统一网关响应对象`
- `策略决策对象`
- `审计事件对象`
- `应用接入对象`

## 18. 结论

企业 AI 安全网关的接口协议，不能只围绕“调用模型”设计，而必须围绕以下对象来设计：

1. `调用身份`
2. `业务上下文`
3. `风险决策`
4. `审计留痕`
5. `模型路由`

因此，真正的企业版协议不只是：

- “帮我把 prompt 发给模型”

而应该是：

- “在明确身份、场景、策略和审计要求下，安全地处理一次 AI 调用”

这也是项目从 demo 走向企业可部署产品的关键一步。
