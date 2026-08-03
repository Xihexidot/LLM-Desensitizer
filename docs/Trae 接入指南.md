# Trae 接入企业 AI 安全网关指南

## 1. 快速开始

### 1.1 后端服务地址设置
后端默认运行在 `http://localhost:8080`

如果是本地开发，则:
```
后端地址: http://localhost:8080
```

如果是部署在服务器上，则替换为服务器地址和对应端口。

### 1.2 获取 API Key
系统已预置一个测试 API Key:
```
sk-test-abc123xyz
```

如需生成新的 API Key，可以调用管理接口（仅开发/测试环境）:

```bash
# 创建新的 API Key（绑定身份）
curl -X POST http://localhost:8080/admin/api-keys \
-H "Content-Type: application/json" \
-d '{
  "name": "Trae 集成应用",
  "tenantId": "default",
  "userId": "trae-user-001",
  "department": "技术部"
}'
```

响应示例:
```json
{
  "id": "xxx",
  "key": "sk-8f2e7a8c-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "name": "Trae 集成应用",
  "tenantId": "default",
  "userId": "trae-user-001",
  "department": "技术部",
  "createdAt": "2025-06-17T..."
}
```

**注意**: `key` 仅返回一次，请妥善保管！

---

## 2. API 调用方式

### 2.1 认证方式
所有 `/gateway/v1/*` 端点都需要在请求头中携带 API Key，使用 **Bearer Token** 格式:

```
Authorization: Bearer <你的 API Key>
```

### 2.2 OpenAI 兼容聊天接口
**URL**: `POST /gateway/v1/chat/completions`

**请求示例**:
```bash
curl -X POST http://localhost:8080/gateway/v1/chat/completions \
-H "Content-Type: application/json" \
-H "Authorization: Bearer sk-test-abc123xyz" \
-d '{
  "model": "gpt-3.5-turbo",
  "messages": [
    {"role": "user", "content": "请帮我生成一段介绍"}
  ]
}'
```

在 Trae 中配置时:
- 模型供应商（Provider）: 选择 `OpenAI (兼容)`
- Base URL: 设置为 `http://localhost:8080/gateway/v1`
- API Key: 填入上面生成的 API Key

---

## 3. 身份绑定与审计

### 3.1 身份绑定机制
API Key 绑定了以下身份信息（在创建时指定）:
- `tenantId`: 租户/组织 ID
- `userId`: 用户 ID
- `department`: 部门

这些信息会自动记录到审计日志中，无需在每次请求时重复传递。

### 3.2 可选：请求时自定义身份覆盖
如果需要在单次请求中覆盖默认身份，可以在请求头中添加:

```
X-User-Id: user-123
X-Department: 研发部
X-Tenant-Id: my-org
```

这些值会优先于 API Key 绑定的默认身份。

---

## 4. 可用的端点列表

| 端点路径 | 方法 | 说明 |
|---------|------|------|
| `/gateway/v1/chat/completions` | POST | OpenAI 兼容的聊天接口 |
| `/gateway/v1/files/tasks` | POST | 创建异步文件处理任务 |
| `/gateway/v1/files/tasks/{taskId}` | GET | 查询文件任务状态 |
| `/gateway/v1/audit/events` | GET | 查询审计事件日志 |
| `/plugin/audit-check` | POST | 插件使用的敏感检测接口（无需 API Key） |

---

## 5. 完整的 Trae 配置步骤

### 步骤 1: 启动后端服务
```bash
# 在项目根目录执行
./mvnw spring-boot:run
# 或
mvn spring-boot:run
```

### 步骤 2: 在 Trae 中配置 API
1. 打开 Trae 设置 → 大模型配置
2. 选择 `OpenAI (兼容)` 类型
3. Base URL: `http://localhost:8080/gateway/v1`
4. API Key: `sk-test-abc123xyz`
5. 模型名称: 根据你的 LLM 配置填写，如 `gpt-3.5-turbo`、`deepseek-chat` 等
6. 保存并测试连接

### 步骤 3: 验证配置是否生效
发送一条测试消息:
- 如果消息中包含敏感信息（如手机号），系统会自动脱敏或弹出提示
- 查看后端日志，确认审计记录正常生成
