# LLM 调用监控 - 接口调用说明文档

> 版本：v1.0 · 基础路径：`/gateway/v1/monitor`

## 1. 通用约定

### 1.1 认证与权限

所有监控接口**必须**携带角色请求头，后端白名单校验：

```
X-Monitor-Role: AUDITOR | ADMIN | OPERATOR
```

- 白名单角色：`AUDITOR`（安全审计）、`ADMIN`（运维管理）、`OPERATOR`（操作员）
- 匿名或非白名单角色：返回 `HTTP 403`
- 角色值大小写不敏感（服务端 `trim + toUpperCase` 归一）

### 1.2 数据口径

- 所有统计均以 `gateway_audit_event` 表为数据源，统计范围为**当日**（`timestamp >= 当日 00:00:00`）
- 渠道口径：`BROWSER_PLUGIN`（浏览器插件）/ `backend-api`（网关 API）
- 平台口径：原始供应商标识经 `ProviderNormalizer` 归一为 12 个标准平台分组

## 2. 接口列表

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/gateway/v1/monitor/overview` | GET | 当日概览（总量、渠道、分平台、风险、决策、异常数） |
| `/gateway/v1/monitor/trend?hours=24` | GET | 按小时趋势（默认 24 点，上限 48） |
| `/gateway/v1/monitor/anomalies` | GET | 当日异常告警列表 |

---

## 3. 当日概览

### 3.1 请求

```
GET /gateway/v1/monitor/overview
X-Monitor-Role: AUDITOR
```

### 3.2 响应 200

```json
{
  "date": "2026-08-03",
  "todayTotal": 10,
  "pluginTotal": 6,
  "apiTotal": 4,
  "byChannel": [
    { "channel": "BROWSER_PLUGIN", "cnt": 6 },
    { "channel": "backend-api", "cnt": 4 }
  ],
  "byProvider": [
    { "code": "DEEPSEEK", "name": "DeepSeek", "count": 4, "pluginCount": 2, "apiCount": 2 },
    { "code": "OPENAI",  "name": "OpenAI (ChatGPT)", "count": 2, "pluginCount": 2, "apiCount": 0 }
  ],
  "byRiskLevel": [
    { "input_risk_level": "HIGH", "cnt": 4 }
  ],
  "byDecision": [
    { "decision_action": "BLOCK", "cnt": 5 }
  ],
  "anomalyCount": 0
}
```

### 3.3 字段说明

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `date` | string | 统计日期（`yyyy-MM-dd`） |
| `todayTotal` | number | 当日请求总次数 |
| `pluginTotal` | number | 浏览器插件渠道次数 |
| `apiTotal` | number | 网关 API 渠道次数 |
| `byChannel` | array | 渠道维度分布 |
| `byProvider` | array | 分平台统计，按次数降序；`code` 为标准平台码，`pluginCount`/`apiCount` 渠道拆分 |
| `byRiskLevel` | array | 输入风险等级分布 |
| `byDecision` | array | 决策动作分布 |
| `anomalyCount` | number | 当日异常告警数量 |

---

## 4. 按小时趋势

### 4.1 请求

```
GET /gateway/v1/monitor/trend?hours=24
X-Monitor-Role: AUDITOR
```

| 参数 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `hours` | 否 | 24 | 窗口小时数，`1 ~ 48`，超范围自动截断 |

### 4.2 响应 200

```json
{
  "date": "2026-08-03",
  "hours": 24,
  "points": [
    { "hour": "00:00", "plugin": 0, "api": 0, "total": 0 },
    { "hour": "01:00", "plugin": 1, "api": 0, "total": 1 }
  ]
}
```

### 4.3 字段说明

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `points[].hour` | string | 小时标签（`HH:00`），无数据的时段补零 |
| `points[].plugin` | number | 该小时插件渠道次数 |
| `points[].api` | number | 该小时 API 渠道次数 |
| `points[].total` | number | 该小时总次数（= plugin + api） |

---

## 5. 异常告警

### 5.1 请求

```
GET /gateway/v1/monitor/anomalies
X-Monitor-Role: ADMIN
```

### 5.2 响应 200

```json
{
  "date": "2026-08-03",
  "count": 1,
  "items": [
    {
      "id": "hf-20260803-0001",
      "level": "HIGH",
      "type": "HIGH_FREQUENCY",
      "title": "高频调用",
      "detail": "员工账号 u-***（共 20 次）在 24 小时内高频调用外部模型，疑似脚本化访问。",
      "count": 20,
      "timeWindow": "24 小时",
      "generatedAt": "2026-08-03 20:00:01"
    }
  ]
}
```

### 5.3 告警类型与等级

| `type` | `level` | 触发条件 |
| --- | --- | --- |
| `HIGH_FREQUENCY` | HIGH | 单员工当日调用 ≥ 20 次 |
| `RISK_SPIKE` | HIGH | 当日高风险输入请求 ≥ 10 次 |
| `BLOCK_SPIKE` | MEDIUM | 当日阻断事件 ≥ 10 次 |
| `UNKNOWN_PROVIDER` | MEDIUM | 当日调用未登记平台 ≥ 5 次 |

`items` 按等级降序返回（HIGH 优先）。`detail` 中员工账号已脱敏（前缀掩码）。

### 5.4 错误码

| 状态码 | 说明 |
| --- | --- |
| `403` | 未携带或携带非白名单 `X-Monitor-Role`，响应体：`{"error": "forbidden: monitor role required"}` |
| `404` | 接口路径不存在 |
| `500` | 服务端异常（数据库不可用等） |
