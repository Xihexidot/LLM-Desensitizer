<script setup>
  import { ref, onMounted, onUnmounted, computed } from "vue";
  import {
    Chart as ChartJS,
    ArcElement,
    Tooltip,
    Legend,
    CategoryScale,
    LinearScale,
    BarElement,
  } from "chart.js";
  import { Pie, Bar } from "vue-chartjs";
  import { API_BASE_URL } from "../config";

  ChartJS.register(
    ArcElement,
    Tooltip,
    Legend,
    CategoryScale,
    LinearScale,
    BarElement,
  );

  // ============ 权限凭证 ============
  // 监控接口仅允许安全审计 / 运维管理相关角色访问（后端 X-Monitor-Role 白名单校验）。
  // 角色选择持久化到 localStorage，企业接入 SSO 后可替换为从会话令牌解析。
  const ROLE_KEY = "monitor_role";
  const ROLE_OPTIONS = [
    { value: "AUDITOR", label: "安全审计" },
    { value: "ADMIN", label: "运维管理" },
    { value: "OPERATOR", label: "操作员" },
  ];
  const role = ref(
    (() => {
      const saved = localStorage.getItem(ROLE_KEY);
      return ROLE_OPTIONS.some((r) => r.value === saved) ? saved : "AUDITOR";
    })(),
  );
  const forbidden = ref(false);

  // ============ 数据状态 ============
  const overview = ref({
    todayTotal: 0,
    pluginTotal: 0,
    apiTotal: 0,
    byProvider: [],
    byRiskLevel: [],
    byDecision: [],
    anomalyCount: 0,
  });
  const trend = ref({ points: [] });
  const anomalies = ref({ count: 0, items: [] });
  const loading = ref(false);
  const error = ref("");
  const lastUpdated = ref("");
  let refreshTimer = null;

  const PROVIDER_COLORS = [
    "#6366f1",
    "#10b981",
    "#f59e0b",
    "#ef4444",
    "#8b5cf6",
    "#06b6d4",
    "#ec4899",
    "#84cc16",
    "#f97316",
    "#3b82f6",
    "#14b8a6",
    "#94a3b8",
  ];
  const LEVEL_LABELS = {
    HIGH: "高风险",
    MEDIUM: "中风险",
    LOW: "低风险",
  };

  function roleHeaders() {
    return {
      "X-Monitor-Role": role.value,
      Accept: "application/json",
    };
  }

  async function fetchJson(url) {
    const res = await fetch(url, { headers: roleHeaders() });
    if (res.status === 403) {
      forbidden.value = true;
      throw new Error(
        "当前角色无权访问监控数据，请切换为安全审计 / 运维管理角色",
      );
    }
    forbidden.value = false;
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
  }

  async function fetchAll() {
    loading.value = true;
    error.value = "";
    try {
      const [ov, tr, an] = await Promise.all([
        fetchJson(`${API_BASE_URL}/gateway/v1/monitor/overview`),
        fetchJson(`${API_BASE_URL}/gateway/v1/monitor/trend?hours=24`),
        fetchJson(`${API_BASE_URL}/gateway/v1/monitor/anomalies`),
      ]);
      overview.value = ov;
      trend.value = tr;
      anomalies.value = an;
      lastUpdated.value = new Date().toLocaleTimeString("zh-CN");
    } catch (e) {
      error.value = "加载失败: " + e.message;
    } finally {
      loading.value = false;
    }
  }

  function changeRole() {
    try {
      localStorage.setItem(ROLE_KEY, role.value);
    } catch {}
    forbidden.value = false;
    fetchAll();
  }

  onMounted(() => {
    fetchAll();
    refreshTimer = setInterval(fetchAll, 10000);
  });
  onUnmounted(() => {
    if (refreshTimer) clearInterval(refreshTimer);
  });

  // ============ 图表数据 ============
  const providerChartData = computed(() => {
    const list = (overview.value.byProvider || []).filter((p) => p.count > 0);
    if (!list.length) return null;
    return {
      labels: list.map((p) => p.name),
      datasets: [
        {
          data: list.map((p) => p.count),
          backgroundColor: list.map(
            (_, i) => PROVIDER_COLORS[i % PROVIDER_COLORS.length],
          ),
          borderWidth: 1,
        },
      ],
    };
  });

  const trendChartData = computed(() => {
    const pts = trend.value.points || [];
    if (!pts.length) return null;
    return {
      labels: pts.map((p) => p.hour),
      datasets: [
        {
          label: "浏览器插件",
          data: pts.map((p) => p.plugin),
          backgroundColor: "#6366f1",
          borderRadius: 2,
        },
        {
          label: "网关 API",
          data: pts.map((p) => p.api),
          backgroundColor: "#10b981",
          borderRadius: 2,
        },
      ],
    };
  });

  const chartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        position: "bottom",
        labels: { usePointStyle: true, boxWidth: 10 },
      },
    },
  };
  const trendChartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        position: "bottom",
        labels: { usePointStyle: true, boxWidth: 10 },
      },
    },
    scales: {
      x: { stacked: true, grid: { display: false } },
      y: { stacked: true, beginAtZero: true, ticks: { precision: 0 } },
    },
  };

  function levelClass(level) {
    return (level || "").toLowerCase();
  }
</script>

<template>
  <div class="provider-page">
    <div class="page-head">
      <h2>外部 LLM 调用监控</h2>
      <div class="head-actions">
        <span class="refresh-hint"
          >每 10 秒自动刷新 · 更新于 {{ lastUpdated || "-" }}</span
        >
        <label class="role-picker">
          监控角色
          <select
            v-model="role"
            @change="changeRole"
          >
            <option
              v-for="r in ROLE_OPTIONS"
              :key="r.value"
              :value="r.value"
            >
              {{ r.label }} ({{ r.value }})
            </option>
          </select>
        </label>
        <button
          class="btn-refresh"
          @click="fetchAll"
          :disabled="loading"
        >
          {{ loading ? "刷新中..." : "立即刷新" }}
        </button>
      </div>
    </div>

    <div class="info-banner">
      本页面仅统计企业员工通过内部插件与统一网关发往外部大模型平台的调用次数，按平台类型拆分，
      用于内部安全审计与异常风险检测。员工敏感信息已脱敏处理，符合数据隐私合规要求。
      仅限安全审计 / 运维管理角色访问。
    </div>

    <div
      v-if="forbidden"
      class="gw-error"
    >
      权限不足：当前角色（{{ role }}）无权访问监控数据。请切换为
      AUDITOR（安全审计）或 ADMIN（运维管理）角色后重试。
    </div>
    <div
      v-else-if="error"
      class="gw-error"
    >
      {{ error }}
    </div>

    <!-- 指标卡片 -->
    <div class="stats-row">
      <div class="stat-card primary">
        <div class="label">今日总请求</div>
        <div class="value">{{ overview.todayTotal }}</div>
      </div>
      <div class="stat-card plugin">
        <div class="label">浏览器插件</div>
        <div class="value">{{ overview.pluginTotal }}</div>
      </div>
      <div class="stat-card api">
        <div class="label">网关 API</div>
        <div class="value">{{ overview.apiTotal }}</div>
      </div>
      <div class="stat-card provider">
        <div class="label">调用平台数</div>
        <div class="value">{{ overview.byProvider.length }}</div>
      </div>
      <div class="stat-card danger">
        <div class="label">异常告警</div>
        <div class="value">{{ overview.anomalyCount }}</div>
      </div>
    </div>

    <!-- 图表区 -->
    <div class="charts-row">
      <div class="chart-box">
        <div class="chart-title">员工访问 LLM 服务分布（按平台）</div>
        <div
          v-if="providerChartData"
          style="height: 240px"
        >
          <Pie
            :data="providerChartData"
            :options="chartOptions"
          />
        </div>
        <div
          v-else
          class="chart-empty"
        >
          今日暂无调用数据
        </div>
      </div>
      <div class="chart-box">
        <div class="chart-title">近 24 小时调用趋势（插件 / API 堆叠）</div>
        <div
          v-if="trendChartData"
          style="height: 240px"
        >
          <Bar
            :data="trendChartData"
            :options="trendChartOptions"
          />
        </div>
        <div
          v-else
          class="chart-empty"
        >
          今日暂无调用数据
        </div>
      </div>
    </div>

    <!-- 分平台统计 -->
    <div class="section-title">
      分平台调用统计
      <span class="section-sub">按标准化平台聚合，含插件 / API 渠道拆分</span>
    </div>
    <div
      v-if="overview.byProvider?.length"
      class="provider-grid"
    >
      <div
        v-for="p in overview.byProvider"
        :key="p.code"
        class="provider-card"
      >
        <div class="p-name">{{ p.name }}</div>
        <div class="p-count">{{ p.count }} <span class="unit">次</span></div>
        <div class="p-breakdown">
          <span class="bd-item plugin">插件 {{ p.pluginCount }}</span>
          <span class="bd-item api">API {{ p.apiCount }}</span>
        </div>
      </div>
    </div>
    <div
      v-else-if="!loading"
      class="empty"
    >
      今日暂无外部 LLM 调用记录。员工通过浏览器插件或网关 API
      调用模型后，统计数据将在此展示。
    </div>

    <!-- 异常告警面板 -->
    <div class="section-title">
      异常风险告警
      <span class="section-sub"
        >当日异常检测结果（{{ anomalies.count }} 条）</span
      >
    </div>
    <div
      v-if="anomalies.items?.length"
      class="anomaly-list"
    >
      <div
        v-for="a in anomalies.items"
        :key="a.id"
        class="anomaly-item"
        :class="'level-' + levelClass(a.level)"
      >
        <div class="anomaly-head">
          <span
            class="level-tag"
            :class="'tag-' + levelClass(a.level)"
          >
            {{ LEVEL_LABELS[a.level] || a.level }}
          </span>
          <span class="a-title">{{ a.title }}</span>
          <span class="a-count">{{ a.count }} 次</span>
        </div>
        <div class="a-detail">{{ a.detail }}</div>
        <div class="a-meta">
          <span>类型: {{ a.type }}</span>
          <span>时间窗口: {{ a.timeWindow }}</span>
          <span>检测时间: {{ a.generatedAt }}</span>
        </div>
      </div>
    </div>
    <div
      v-else-if="!loading"
      class="empty"
    >
      今日未检测到异常调用行为
    </div>

    <div class="note">
      说明：此页面仅展示聚合统计数据，用于安全审计与异常检测。后端不转发员工请求到外部模型，
      员工原始内容在审计环节已脱敏并加密存储。
    </div>
  </div>
</template>

<style scoped>
  .provider-page {
    width: 100%;
  }
  .page-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 14px;
    flex-wrap: wrap;
    gap: 10px;
  }
  .page-head h2 {
    margin: 0;
    font-size: 1.3rem;
  }
  .head-actions {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }
  .refresh-hint {
    font-size: 0.78rem;
    color: #94a3b8;
  }
  .role-picker {
    font-size: 0.8rem;
    color: #64748b;
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }
  .role-picker select {
    padding: 5px 8px;
    border: 1px solid #e2e8f0;
    border-radius: 8px;
    font-size: 0.82rem;
    background: #fff;
    color: #1e293b;
    cursor: pointer;
  }
  .btn-refresh {
    padding: 6px 16px;
    border: 1px solid #e2e8f0;
    border-radius: 8px;
    background: #f8fafc;
    cursor: pointer;
    font-size: 0.85rem;
  }
  .btn-refresh:disabled {
    opacity: 0.5;
  }
  .gw-error {
    color: #ef4444;
    padding: 12px;
    background: #fef2f2;
    border-radius: 8px;
    margin-bottom: 12px;
  }

  .info-banner {
    background: #f0f9ff;
    border: 1px solid #bae6fd;
    border-radius: 8px;
    padding: 12px 16px;
    font-size: 0.84rem;
    color: #0369a1;
    margin-bottom: 16px;
    line-height: 1.5;
  }

  .stats-row {
    display: grid;
    grid-template-columns: repeat(5, 1fr);
    gap: 14px;
    margin-bottom: 24px;
  }
  @media (max-width: 1100px) {
    .stats-row {
      grid-template-columns: repeat(3, 1fr);
    }
  }
  @media (max-width: 700px) {
    .stats-row {
      grid-template-columns: repeat(2, 1fr);
    }
  }
  .stat-card {
    border-radius: 12px;
    padding: 18px 20px;
    text-align: center;
    border: 1px solid #e2e8f0;
  }
  .stat-card .label {
    font-size: 0.82rem;
    color: #64748b;
    margin-bottom: 6px;
  }
  .stat-card .value {
    font-size: 2.2rem;
    font-weight: 800;
  }
  .stat-card.primary {
    background: #eff6ff;
    border-color: #bfdbfe;
  }
  .stat-card.primary .value {
    color: #2563eb;
  }
  .stat-card.plugin {
    background: #eef2ff;
    border-color: #c7d2fe;
  }
  .stat-card.plugin .value {
    color: #4f46e5;
  }
  .stat-card.api {
    background: #ecfdf5;
    border-color: #a7f3d0;
  }
  .stat-card.api .value {
    color: #059669;
  }
  .stat-card.provider {
    background: #f5f3ff;
    border-color: #ddd6fe;
  }
  .stat-card.provider .value {
    color: #7c3aed;
  }
  .stat-card.danger {
    background: #fef2f2;
    border-color: #fecaca;
  }
  .stat-card.danger .value {
    color: #dc2626;
  }

  .charts-row {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
    margin-bottom: 24px;
  }
  @media (max-width: 900px) {
    .charts-row {
      grid-template-columns: 1fr;
    }
  }
  .chart-box {
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    padding: 16px;
  }
  .chart-title {
    font-size: 0.85rem;
    color: #334155;
    margin-bottom: 12px;
    font-weight: 600;
  }
  .chart-empty {
    height: 240px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #94a3b8;
    font-size: 0.85rem;
  }

  .section-title {
    font-size: 1rem;
    font-weight: 600;
    color: #334155;
    margin-bottom: 12px;
    margin-top: 8px;
  }
  .section-sub {
    font-size: 0.78rem;
    color: #94a3b8;
    font-weight: 400;
    margin-left: 8px;
  }

  .provider-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 12px;
    margin-bottom: 24px;
  }
  @media (max-width: 1100px) {
    .provider-grid {
      grid-template-columns: repeat(3, 1fr);
    }
  }
  @media (max-width: 900px) {
    .provider-grid {
      grid-template-columns: repeat(2, 1fr);
    }
  }
  .provider-card {
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    padding: 16px;
    background: #fff;
    text-align: center;
  }
  .p-name {
    font-size: 0.92rem;
    font-weight: 700;
    color: #1e293b;
    margin-bottom: 8px;
  }
  .p-count {
    font-size: 2rem;
    font-weight: 800;
    color: #6366f1;
  }
  .unit {
    font-size: 0.85rem;
    font-weight: 400;
    color: #94a3b8;
  }
  .p-breakdown {
    display: flex;
    justify-content: center;
    gap: 10px;
    margin-top: 8px;
    font-size: 0.76rem;
  }
  .bd-item.plugin {
    color: #4f46e5;
    background: #eef2ff;
    padding: 2px 8px;
    border-radius: 10px;
  }
  .bd-item.api {
    color: #059669;
    background: #ecfdf5;
    padding: 2px 8px;
    border-radius: 10px;
  }

  .anomaly-list {
    display: flex;
    flex-direction: column;
    gap: 10px;
    margin-bottom: 24px;
  }
  .anomaly-item {
    border: 1px solid #e2e8f0;
    border-left-width: 4px;
    border-radius: 10px;
    padding: 12px 16px;
    background: #fff;
  }
  .anomaly-item.level-high {
    border-left-color: #ef4444;
  }
  .anomaly-item.level-medium {
    border-left-color: #f59e0b;
  }
  .anomaly-item.level-low {
    border-left-color: #3b82f6;
  }
  .anomaly-head {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }
  .level-tag {
    padding: 2px 10px;
    border-radius: 10px;
    font-size: 0.75rem;
    font-weight: 700;
    color: #fff;
  }
  .tag-high {
    background: #ef4444;
  }
  .tag-medium {
    background: #f59e0b;
  }
  .tag-low {
    background: #3b82f6;
  }
  .a-title {
    font-size: 0.9rem;
    font-weight: 600;
    color: #1e293b;
  }
  .a-count {
    margin-left: auto;
    font-size: 0.82rem;
    color: #64748b;
    font-weight: 600;
  }
  .a-detail {
    margin-top: 6px;
    font-size: 0.82rem;
    color: #475569;
    line-height: 1.5;
  }
  .a-meta {
    margin-top: 6px;
    display: flex;
    gap: 16px;
    font-size: 0.74rem;
    color: #94a3b8;
    flex-wrap: wrap;
  }

  .empty {
    text-align: center;
    color: #94a3b8;
    padding: 24px 0;
    font-size: 0.88rem;
  }
  .note {
    margin-top: 8px;
    font-size: 0.75rem;
    color: #94a3b8;
    line-height: 1.5;
  }
</style>
