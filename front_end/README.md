# API敏感信息脱敏系统 - 前端界面

这是API敏感信息脱敏系统的前端界面，基于Vue 3和Vite开发，提供直观的用户界面用于管理和监控敏感信息脱敏过程。

## 功能特性

- 敏感信息检测与脱敏操作界面
- 支持多种敏感信息类型的识别与处理
- 可视化脱敏结果展示
- 自定义脱敏规则配置
- 脱敏历史记录查询
- 响应式设计，支持多设备访问

## 技术栈

- **前端框架**：Vue 3 (Composition API)
- **构建工具**：Vite
- **样式处理**：原生 CSS
- **HTTP 客户端**：fetch API（无额外依赖）
- **图表**：Chart.js + vue-chartjs
- **Markdown 渲染**：markdown-it
- **代码高亮**：highlight.js
- **PDF 导出**：jsPDF + html2canvas

## 项目结构

```
front_end/
├── public/                       # 静态资源与样本数据
│   ├── sample_sensitive_30k.txt  # 30k 敏感数据样本
│   └── vite.svg
├── src/
│   ├── assets/                   # 静态资源
│   │   └── vue.svg
│   ├── components/               # Vue 组件
│   │   ├── LlmDesensitization.vue      # 核心脱敏交互页
│   │   ├── LlmDetectionResults.vue     # 检测结果可视化
│   │   ├── LlmFeatureCards.vue         # 特性卡片展示
│   │   ├── LlmProcessingProgress.vue   # 处理进度动画
│   │   ├── LlmResultsPanel.vue         # 结果面板
│   │   ├── SensitiveRules.vue          # 自定义规则管理
│   │   ├── Sidebar.vue                  # 侧边导航
│   │   ├── ConversationHistory.vue     # 会话历史
│   │   ├── DashboardStats.vue          # 仪表盘统计
│   │   └── HelloWorld.vue              # 入门组件
│   ├── utils/                    # 工具函数
│   │   └── llmReportExport.js    # 报告导出工具
│   ├── App.vue                   # 应用主入口
│   ├── config.js                 # 前端配置文件
│   ├── main.js                   # 应用挂载入口
│   └── style.css                 # 全局样式
├── index.html                    # HTML 入口
├── vite.config.js                # Vite 构建配置
├── package.json                  # 项目依赖
├── package-lock.json
└── README.md
```

> 说明：当前前端未使用 Vue Router、Pinia/Vuex 状态管理，路由与状态均由 App.vue 通过响应式变量集中管理。

## 快速开始

### 环境要求

- Node.js 16.x 或更高版本
- npm 8.x 或更高版本

### 安装与运行

1. 安装依赖

```bash
npm install
```

2. 运行开发服务器

```bash
npm run dev
```

默认情况下，开发服务器将在 http://localhost:5173 启动。

3. 构建生产版本

```bash
npm run build
```

构建后的文件将输出到 `dist` 目录。

## API 集成

前端应用与后端服务通过 RESTful API 交互，主要接口包括：

- 敏感信息检测与脱敏：`/desensitize/*`
- LLM 代理调用：`/api/llm/proxy`
- 自定义规则管理：`/rules`
- 敏感词典管理：`/dict`

后端服务默认运行在 `http://localhost:8080`。API 基础 URL 在 `src/config.js` 中配置。

## 开发指南

### 组件开发

1. 在 `src/components` 目录下创建新组件
2. 使用 Vue 3 组合式 API (`<script setup>`) 进行开发
3. 遵循组件命名规范（PascalCase）

### API 调用

- 组件内直接使用 `fetch` 发起 HTTP 请求
- 请求由组件生命周期或事件处理函数触发
- 无独立的 services 抽象层

## 代码规范

- 遵循 Vue 3 官方推荐的最佳实践
- 为组件和关键函数添加适当的文档注释

## 贡献指南

欢迎对本项目进行贡献！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 开启Pull Request

## 许可证

[MIT](https://choosealicense.com/licenses/mit/)
