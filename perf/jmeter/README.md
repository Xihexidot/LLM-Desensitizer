# JMeter 性能测试资产说明

本目录提供比赛现场可复跑的 JMeter 压测资产，覆盖以下 3 条核心业务链路：

1. `POST /desensitize/text`
2. `POST /plugin/audit-check`
3. `GET /gateway/v1/monitor/{overview,trend,anomalies}`

## 选型理由

- 项目技术栈为 `Spring Boot + HTTP JSON API`，JMeter 对接口压测、分阶段并发、结果聚合报告支持成熟。
- 适合比赛现场快速演示：可直接使用非 GUI 模式批量执行，并导出 HTML 报告。
- 与项目当前日志、JUnit 性能基线可形成交叉验证。

## 默认参数

| 场景 | 默认并发 | Ramp-Up | 持续时长 |
| --- | ---: | ---: | ---: |
| 文本脱敏链路 | 20 | 60s | 300s |
| 插件审计链路 | 20 | 60s | 300s |
| 监控聚合链路 | 10 | 30s | 180s |

## 梯度加压建议

| 阶段 | 参数建议 | 目标 |
| --- | --- | --- |
| 基线 | `20/20/10` 用户 | 建立稳定基线，观察错误率与 P95 |
| 峰值 | `30/30/15` 用户 | 模拟比赛展示时的高频访问 |
| 压力 | `50/50/20` 用户 | 识别瓶颈和响应时间拐点 |

说明：顺序为 `文本脱敏 / 插件审计 / 监控聚合`。

## 执行方式

1. 启动项目服务，默认地址 `http://127.0.0.1:8080`
2. 若监控接口开启鉴权，请保留 `X-Monitor-Role=AUDITOR`
3. 在已安装 JMeter 的环境执行：

```powershell
pwsh .\perf\jmeter\run-jmeter.ps1
```

或自定义并发参数：

```powershell
pwsh .\perf\jmeter\run-jmeter.ps1 `
  -UsersDesensitize 30 `
  -UsersPlugin 30 `
  -UsersMonitor 15
```

## 输出结果

- `perf/jmeter/results/*.jtl`: 原始采样结果
- `perf/jmeter/results/*-html/`: HTML 聚合报告

## 说明

- 当前仓库内已补充 JVM 侧可复现性能测试源码，作为无 JMeter 环境下的替代验证。
- 若比赛现场需要展示更严格的全链路压测，可在网关前接入 Nginx 反向代理后复用本计划继续扩容测试。
