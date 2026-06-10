<script setup>
  import { ref, onMounted } from "vue";
  import LlmDesensitization from "./components/LlmDesensitization.vue";
  import Sidebar from "./components/Sidebar.vue";
  import ConversationHistory from "./components/ConversationHistory.vue";
  import DashboardStats from "./components/DashboardStats.vue";
  import AuditList from "./components/AuditList.vue";
  import AuditDetail from "./components/AuditDetail.vue";
  import RiskPolicy from "./components/RiskPolicy.vue";
  import ProviderStatus from "./components/ProviderStatus.vue";
  import { API_BASE_URL } from "./config";

  // 视图控制 — 默认首页为仪表盘
  const currentView = ref("dashboard");

  function showHome() {
    currentView.value = "home";
  }

  function showDashboard() {
    currentView.value = "dashboard";
  }

  function showAudit() {
    currentView.value = "audit";
  }

  function showTool() {
    currentView.value = "tool";
  }

  function showPolicy() {
    currentView.value = "policy";
  }

  function showProviders() {
    currentView.value = "providers";
  }

  // 审计详情
  const selectedAuditEventId = ref(null);
  function showAuditDetail(eventId) {
    selectedAuditEventId.value = eventId;
    currentView.value = "audit-detail";
  }
  function backToAuditList() {
    selectedAuditEventId.value = null;
    currentView.value = "audit";
  }

  // 健康检查
  const healthStatus = ref("");
  const healthLoading = ref(false);

  // 执行健康检查
  async function checkHealth() {
    healthLoading.value = true;
    console.log(`正在请求健康检查端点: ${API_BASE_URL}/desensitize/health`);

    try {
      // 简化fetch配置，移除可能导致问题的选项
      const response = await fetch(`${API_BASE_URL}/desensitize/health`, {
        method: "GET",
        headers: {
          Accept: "*/*",
        },
        // 移除mode和credentials配置，让浏览器使用默认值
        // 这通常对本地开发更友好
      });

      console.log("健康检查响应状态:", response.status);
      console.log("健康检查响应头:", response.headers);

      if (response.ok) {
        const data = await response.text();
        healthStatus.value = `✅ 服务正常运行: ${data}`;
      } else {
        healthStatus.value = `❌ 服务异常 (状态码: ${response.status})`;
      }
    } catch (error) {
      console.error("健康检查请求错误:", error);
      healthStatus.value = `❌ 连接失败: ${error.message}`;
      // 添加更多调试信息
      console.log("错误类型:", error.name);
      console.log("API_BASE_URL配置:", API_BASE_URL);
    } finally {
      healthLoading.value = false;
    }
  }

  function scrollToLlm() {
    const el = document.getElementById("section-llm");
    if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  const STORAGE_KEY = "conversation_history_v1";
  const histories = ref([]);
  const activeHistoryId = ref(null);
  const showHistorySection = ref(false);
  const THEME_KEY = "app_theme";
  const theme = ref("light");

  function loadHistory() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      histories.value = raw ? JSON.parse(raw) : [];
    } catch {
      histories.value = [];
    }
  }

  function persistHistory() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(histories.value));
    } catch {}
  }

  function saveConversation(payload) {
    const item = {
      id: payload.id,
      timestamp: payload.timestamp,
      provider: payload.provider,
      originalPrompt: payload.originalPrompt,
      desensitizedPrompt: payload.desensitizedPrompt,
      responseText: payload.responseText,
    };
    histories.value.push(item);
    if (histories.value.length > 200)
      histories.value = histories.value.slice(-200);
    persistHistory();
  }

  function clearHistory() {
    histories.value = [];
    persistHistory();
  }

  onMounted(() => {
    loadHistory();
    const saved = localStorage.getItem(THEME_KEY);
    if (saved === "dark" || saved === "light") {
      theme.value = saved;
      applyTheme();
    } else {
      applyTheme();
    }
  });

  function jumpToHistory(id) {
    showHistorySection.value = true;
    activeHistoryId.value = id;
    const el = document.getElementById("section-history");
    if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function applyTheme() {
    const root = document.documentElement;
    root.classList.remove("light", "dark");
    root.classList.add(theme.value);
    try {
      localStorage.setItem(THEME_KEY, theme.value);
    } catch {}
  }

  function toggleTheme() {
    theme.value = theme.value === "light" ? "dark" : "light";
    applyTheme();
  }
</script>

<template>
  <div class="layout">
    <Sidebar
      @check-health="checkHealth"
      @scroll-llm="scrollToLlm"
      :healthStatus="healthStatus"
      :healthLoading="healthLoading"
      :histories="histories"
      @select-history="jumpToHistory"
      @toggle-theme="toggleTheme"
      @show-home="showHome"
      @show-dashboard="showDashboard"
      @show-audit="showAudit"
      @show-policy="showPolicy"
      @show-providers="showProviders"
    />
    <div class="content">
      <header class="header">
        <h1>AI 安全网关管理控制台</h1>
        <p>LLM API 敏感信息检测 · 脱敏 · 审计</p>
      </header>

      <main class="main">
        <div
          v-if="currentView === 'dashboard'"
          class="card full-width"
        >
          <DashboardStats />
        </div>

        <div
          v-if="currentView === 'audit'"
          class="card full-width"
        >
          <AuditList @view-detail="showAuditDetail" />
        </div>

        <div
          v-if="currentView === 'audit-detail'"
          class="card full-width"
        >
          <AuditDetail
            :eventId="selectedAuditEventId"
            @back="backToAuditList"
          />
        </div>

        <div
          v-if="currentView === 'policy'"
          class="card full-width"
        >
          <RiskPolicy />
        </div>

        <div
          v-if="currentView === 'providers'"
          class="card full-width"
        >
          <ProviderStatus />
        </div>

        <div
          v-if="currentView === 'tool' || currentView === 'home'"
          class="card full-width"
          id="section-llm"
        >
          <LlmDesensitization
            @conversation-completed="saveConversation"
          />
        </div>

        <div
          v-if="showHistorySection"
          class="card full-width"
          id="section-history"
        >
          <ConversationHistory
            :histories="histories"
            :activeId="activeHistoryId"
            @clear="clearHistory"
          />
        </div>
      </main>

      <footer
        class="footer"
        id="section-about"
      >
        <p>
          ApiSensitivities 测试工具 &copy; {{ new Date().getFullYear() }} -
          HHB&ZSM
        </p>
      </footer>
    </div>
  </div>
</template>

<style>
  * {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
  }

  :root {
    --primary: #3b82f6;
    --primary-hover: #2563eb;
    --secondary: #64748b;
    --success: #10b981;
    --error: #ef4444;
    --warning: #f59e0b;
    --background: #f8fafc;
    --card-bg: #ffffff;
    --text: #1e293b;
    --text-light: #64748b;
    --border: #e2e8f0;
    --shadow:
      0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
  }

  body {
    font-family:
      -apple-system, BlinkMacSystemFont, "Segoe UI", "Roboto", "Oxygen",
      "Ubuntu", "Cantarell", "Fira Sans", "Droid Sans", "Helvetica Neue",
      sans-serif;
    background-color: var(--background);
    color: var(--text);
    line-height: 1.6;
  }

  .layout {
    display: grid;
    grid-template-columns: 280px 1fr;
    min-height: 100vh;
    background-color: var(--background);
  }
  .content {
    width: 100%;
    padding: 24px 32px;
    background: var(--content-bg);
  }

  .header {
    text-align: center;
    margin-bottom: 40px;
    padding: 20px;
    background: var(--header-bg);
    color: var(--text);
    border-radius: 12px;
    box-shadow: var(--shadow);
    width: 75%;
    margin-left: auto;
    margin-right: auto;
  }

  .header h1 {
    font-size: 2.5rem;
    margin-bottom: 10px;
    color: var(--primary);
  }

  .header p {
    font-size: 1.1rem;
    opacity: 0.95;
    color: var(--text-light);
  }

  .main {
    display: grid;
    grid-template-columns: 1fr;
    gap: 30px;
  }

  .card {
    background: var(--card-bg);
    border-radius: 12px;
    padding: 30px;
    box-shadow: var(--shadow);
    border: 1px solid var(--border);
    transition:
      transform 0.2s ease,
      box-shadow 0.2s ease;
    width: 100%;
  }

  .card:hover {
    transform: translateY(-2px);
    box-shadow:
      0 10px 15px -3px rgba(0, 0, 0, 0.1),
      0 4px 6px -2px rgba(0, 0, 0, 0.05);
  }

  .card h2 {
    font-size: 1.5rem;
    margin-bottom: 20px;
    color: var(--primary);
    border-bottom: 2px solid var(--border);
    padding-bottom: 10px;
  }

  h4 {
    color: #1a365d;
    font-weight: 600;
  }
  p {
    color: #4b5563;
  }
  small {
    color: #6b7280;
  }

  .form-group {
    margin-bottom: 20px;
  }

  .form-group label {
    display: block;
    margin-bottom: 8px;
    font-weight: 600;
    color: var(--text);
  }

  .form-group input,
  .form-group textarea,
  .form-group select {
    width: 100%;
    padding: 12px;
    border: 2px solid var(--border);
    border-radius: 8px;
    font-size: 16px;
    transition: border-color 0.2s ease;
    font-family: inherit;
  }

  .form-group input:focus,
  .form-group textarea:focus,
  .form-group select:focus {
    outline: none;
    border-color: var(--primary);
    box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
  }

  textarea {
    resize: vertical;
    min-height: 80px;
  }

  .btn {
    padding: 12px 24px;
    border: none;
    border-radius: 8px;
    font-size: 16px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s ease;
    margin-right: 10px;
    margin-bottom: 10px;
  }

  .btn-primary {
    background-color: var(--primary);
    color: white;
  }

  .btn-primary:hover:not(:disabled) {
    background-color: var(--primary-hover);
    transform: translateY(-1px);
  }

  .btn-primary:disabled {
    background-color: var(--secondary);
    cursor: not-allowed;
    opacity: 0.6;
  }

  .btn-secondary {
    background-color: var(--secondary);
    color: white;
  }

  .btn-secondary:hover {
    background-color: #475569;
  }

  .btn-danger {
    background-color: var(--error);
    color: white;
  }

  .btn-danger:hover {
    background-color: #dc2626;
  }

  .btn-small {
    padding: 8px 16px;
    font-size: 14px;
  }

  .response-card {
    margin-top: 20px;
    padding: 20px;
    background: #f8fafc;
    border-radius: 8px;
    border: 1px solid var(--border);
  }

  .response-card h3 {
    font-size: 1.2rem;
    margin-bottom: 15px;
    color: var(--text);
  }

  .result-section {
    margin-bottom: 20px;
  }

  .result-section p {
    font-weight: 600;
    margin-bottom: 8px;
    color: var(--text);
  }

  .result-section pre {
    background: var(--card-bg);
    padding: 15px;
    border-radius: 6px;
    border: 1px solid var(--border);
    white-space: pre-wrap;
    word-break: break-word;
    font-family: "Courier New", Courier, monospace;
    max-height: 300px;
    overflow-y: auto;
  }

  .result-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 15px;
    margin-bottom: 20px;
    padding: 15px;
    background: #f1f5f9;
    border-radius: 6px;
  }

  .result-meta span {
    font-size: 0.9rem;
    color: var(--text-light);
  }

  .result-meta .status {
    padding: 4px 12px;
    border-radius: 20px;
    font-size: 0.8rem;
    font-weight: 600;
  }

  .entity-list {
    list-style: none;
  }

  .entity-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 10px;
    margin-bottom: 8px;
    background: var(--card-bg);
    border-radius: 6px;
    border-left: 4px solid var(--primary);
  }

  .entity-type {
    font-weight: 600;
    color: var(--primary);
  }

  .entity-text {
    flex: 1;
    margin: 0 10px;
  }

  .entity-position {
    color: var(--text-light);
    font-size: 0.9rem;
  }

  .entity-confidence {
    color: var(--success);
    font-weight: 600;
  }

  .entity-grid {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }

  .entity-badge {
    background: var(--primary);
    color: white;
    padding: 6px 12px;
    border-radius: 20px;
    font-size: 0.9rem;
  }

  .success-message {
    background: #d1fae5;
    color: #065f46;
    padding: 12px;
    border-radius: 6px;
    margin-top: 15px;
    border: 1px solid #a7f3d0;
  }

  .error-message {
    background: #fee2e2;
    color: #991b1b;
    padding: 12px;
    border-radius: 6px;
    margin-top: 15px;
    border: 1px solid #fecaca;
  }

  .result-status {
    padding: 10px;
    border-radius: 6px;
    font-weight: 600;
    text-align: center;
  }

  .result-status.success {
    background: #d1fae5;
    color: #065f46;
  }

  .result-status.error {
    background: #fee2e2;
    color: #991b1b;
  }

  /* 脱敏级别选择样式 */
  .desensitize-level-select {
    width: 100%;
    padding: 8px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background-color: white;
    font-size: 14px;
  }

  .level-description {
    margin-top: 5px;
    font-size: 12px;
    color: var(--text-light);
    font-style: italic;
  }

  /* 批量测试项样式 */
  .batch-item {
    display: flex;
    gap: 10px;
    margin-bottom: 15px;
    align-items: flex-end;
  }

  .batch-item .form-group {
    flex: 1;
    margin-bottom: 0;
  }

  .batch-responses {
    margin-top: 20px;
  }

  .mini-response {
    padding: 15px;
    background: #f8fafc;
    border-radius: 6px;
    border: 1px solid var(--border);
    margin-bottom: 10px;
  }

  .mini-response p {
    margin-bottom: 5px;
    font-size: 0.9rem;
  }

  .footer {
    text-align: center;
    margin-top: 60px;
    padding: 20px;
    color: var(--text-light);
    border-top: 1px solid var(--border);
  }

  @media (min-width: 768px) {
    .main {
      grid-template-columns: 1fr;
    }
  }

  @media (min-width: 1024px) {
    .header h1 {
      font-size: 3rem;
    }

    .card h2 {
      font-size: 1.75rem;
    }
  }

  @media (max-width: 980px) {
    .layout {
      grid-template-columns: 1fr;
    }
  }
</style>
