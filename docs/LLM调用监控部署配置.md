# LLM 调用监控模块 - 部署配置文档

> 版本：v1.0 · 适用模块：外部 LLM 调用监控（前端 `ProviderStatus` + 后端 `/gateway/v1/monitor/**`）

## 1. 模块构成

| 层 | 位置 | 说明 |
| --- | --- | --- |
| 前端组件 | `front_end/src/components/ProviderStatus.vue` | 实时监控页：指标卡 / 平台分布饼图 / 24h 趋势柱状图 / 分平台统计 / 异常告警面板 / 角色凭证 |
| 前端配置 | `front_end/src/config.js` | API 基础地址，支持构建期 `VITE_API_BASE_URL` 注入 |
| 后端控制器 | `MonitorController` | 概览 / 趋势 / 告警三个接口 |
| 后端统计 | `MonitorService` | 当日聚合统计、分平台归一统计、24h 趋势 |
| 后端异常检测 | `MonitorAnomalyService` | 高频调用 / 风险突增 / 阻断突增 / 未知平台四类告警 |
| 平台归一 | `ProviderNormalizer` | 12 个标准平台分组（OPENAI / ANTHROPIC / DEEPSEEK / QWEN / ERNIE / DOUBAO / KIMI / HUNYUAN / GEMINI / PERPLEXITY / OLLAMA / OTHER） |
| 权限拦截 | `MonitorAuthInterceptor`（注册于 `WebConfig`） | 仅放行安全审计 / 运维管理角色 |

## 2. 前置依赖

- JDK 21、Maven 3.9+
- 数据库（内置 H2 或企业 MySQL），自动执行 `schema.sql`（含 `gateway_audit_event` 表与演示数据）
- Node.js 18+（仅前端构建需要）
- `.env` 配置文件（项目根目录，按项目既有规范加载）

## 3. 前端构建与部署

### 3.1 构建

```bash
cd front_end
npm install
npm run build          # 产物输出到 dist/
```

如需指定后端网关地址（企业内部部署时后端与前端可能不在同一域名），构建期注入：

```powershell
$env:VITE_API_BASE_URL="https://gateway.example.com"
npm run build
```

### 3.2 静态托管（Nginx 示例）

`dist/` 为纯静态产物，任意静态服务器均可托管。监控接口为聚合只读接口，跨域由后端 CORS 配置放行（已配置 `http://localhost:*` 与 `http://127.0.0.1:*`）。

```nginx
server {
    listen 80;
    server_name console.example.com;
    root /opt/api-sensitivities/front_end/dist;
    index index.html;

    # 前端路由为视图切换（非路由跳转），仅需根路径
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 后端 API 反向代理（可选，用于同源部署）
    location /gateway/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
    }
}
```

## 4. 后端部署

```bash
# 打包
mvn clean package -DskipTests

# 运行
java -jar target/apisensitivities-*.jar
```

后端启动后自动建表并写入演示数据；`gateway_audit_event` 由网关审计链路持续写入，监控接口按当日聚合统计。

## 5. 权限体系适配

监控接口通过请求头 `X-Monitor-Role` 声明调用方角色，后端白名单校验：

- **白名单角色**：`AUDITOR`（安全审计）、`ADMIN`（运维管理）、`OPERATOR`（操作员）
- **非白名单 / 匿名**：返回 `403`，前端展示"权限不足"提示
- **大小写不敏感**：请求头值会 `trim + toUpperCase` 归一

企业内部接入 SSO 后，前端从会话令牌解析角色并携带该请求头即可；若网关统一鉴权，可由网关注入该头后转发至监控接口。

前端角色选择持久化于 `localStorage["monitor_role"]`，默认 `AUDITOR`，可在页面右上角切换。

## 6. 安全与合规

- 监控页仅展示**聚合统计数据**，不展示员工原始请求内容
- 异常告警中的员工标识使用前缀掩码（如 `u-***`），符合数据隐私合规要求
- 原始内容在审计链路中以密文存储（`ContentCipher`），仅安全审计环节可解密查阅

## 7. 验证清单

| 项 | 验证方式 |
| --- | --- |
| 前端生产构建 | `cd front_end && npm run build` 成功 |
| 后端接口 | 带 `X-Monitor-Role: AUDITOR` 访问 `/gateway/v1/monitor/overview` 返回 200 |
| 权限拦截 | 不带角色头访问返回 403 |
| 演示数据 | 打开监控页可见平台分布与告警面板（基于 `schema.sql` 演示数据） |
