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

  const stats = ref({
    todayTotal: 0,
    byChannel: [],
    byRiskLevel: [],
    byDecision: [],
    byUserAction: [],
  });
  const recentEvents = ref([]);
  const loading = ref(false);
  const expandedRow = ref(null);
  const error = ref("");
  let refreshTimer = null;

  const USER_ACTION_LABELS = {
    DESENSITIZE_AND_SEND: "发送脱敏版",
    SEND_ORIGINAL: "发送原文",
    CANCEL: "取消",
    AUTO: "自动处理",
  };
  const CHANNEL_LABELS = {
    BROWSER_PLUGIN: "浏览器插件",
    "backend-api": "API 调用",
  };

  async function fetchAll() {
    loading.value = true;
    error.value = "";
    try {
      const [statsRes, eventsRes] = await Promise.all([
        fetch(`${API_BASE_URL}/gateway/v1/audit/stats`),
        fetch(`${API_BASE_URL}/gateway/v1/audit/events`),
      ]);
      if (!statsRes.ok) throw new Error("统计接口异常");
      stats.value = await statsRes.json();

      if (eventsRes.ok) {
        const body = await eventsRes.json();
        recentEvents.value = (Array.isArray(body) ? body : []).slice(0, 10);
      }
    } catch (e) {
      error.value = "加载失败: " + e.message;
    } finally {
      loading.value = false;
    }
  }

  onMounted(() => {
    fetchAll();
    refreshTimer = setInterval(fetchAll, 10000);
  });
  onUnmounted(() => {
    if (refreshTimer) clearInterval(refreshTimer);
  });

  function channelCount(name) {
    const item = stats.value.byChannel?.find((c) => c.channel === name);
    return item?.cnt ?? 0;
  }
  function riskCount(level) {
    const item = stats.value.byRiskLevel?.find(
      (r) => r.input_risk_level === level,
    );
    return item?.cnt ?? 0;
  }
  function decisionCount(action) {
    const item = stats.value.byDecision?.find(
      (d) => d.decision_action === action,
    );
    return item?.cnt ?? 0;
  }

  const riskChartData = computed(() => {
    const levels = ["NONE", "LOW", "MEDIUM", "HIGH"];
    const labels = ["无风险", "低", "中", "高"];
    const colors = ["#94a3b8", "#60a5fa", "#f59e0b", "#ef4444"];
    const data = levels.map((l) => riskCount(l));
    if (data.every((d) => d === 0)) return null;
    return { labels, datasets: [{ data, backgroundColor: colors }] };
  });

  const channelChartData = computed(() => {
    const plugin = channelCount("BROWSER_PLUGIN");
    const api = channelCount("backend-api");
    if (plugin === 0 && api === 0) return null;
    return {
      labels: ["浏览器插件", "API 调用"],
      datasets: [
        { data: [plugin, api], backgroundColor: ["#6366f1", "#10b981"] },
      ],
    };
  });

  function formatTime(ts) {
    if (!ts) return "-";
    const d = Array.isArray(ts)
      ? new Date(ts[0], ts[1] - 1, ts[2], ts[3] || 0, ts[4] || 0, ts[5] || 0)
      : new Date(ts);
    return d.toLocaleString("zh-CN");
  }

  function truncated(s, max) {
    if (!s) return "";
    return s.length > max ? s.slice(0, max) + "..." : s;
  }

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
</script>

<template>
  <div class="gw-dashboard">
    <div class="page-head">
      <h2>安全仪表盘</h2>
      <span class="refresh-hint">每 10 秒自动刷新</span>
    </div>

    <div
      v-if="error"
      class="gw-error"
    >
      {{ error }}
    </div>

    <!-- 指标卡片 -->
    <div class="stats-row">
      <div class="stat-card primary">
        <div class="label">今日事件</div>
        <div class="value">{{ stats.todayTotal }}</div>
      </div>
      <div class="stat-card plugin">
        <div class="label">浏览器插件</div>
        <div class="value">{{ channelCount("BROWSER_PLUGIN") }}</div>
      </div>
      <div class="stat-card api">
        <div class="label">API 调用</div>
        <div class="value">{{ channelCount("backend-api") }}</div>
      </div>
      <div class="stat-card danger">
        <div class="label">高风险</div>
        <div class="value">{{ riskCount("HIGH") }}</div>
      </div>
      <div class="stat-card block">
        <div class="label">今日阻断</div>
        <div class="value">{{ decisionCount("BLOCK") }}</div>
      </div>
    </div>

    <!-- 图表区 -->
    <div
      class="charts-row"
      v-if="riskChartData || channelChartData"
    >
      <div
        class="chart-box"
        v-if="riskChartData"
      >
        <div class="chart-title">风险等级分布</div>
        <div style="height: 200px">
          <Pie
            :data="riskChartData"
            :options="chartOptions"
          />
        </div>
      </div>
      <div
        class="chart-box"
        v-if="channelChartData"
      >
        <div class="chart-title">来源渠道分布</div>
        <div style="height: 200px">
          <Pie
            :data="channelChartData"
            :options="chartOptions"
          />
        </div>
      </div>
    </div>

    <!-- 最近审计事件 -->
    <div class="section-title">最近审计事件</div>
    <div
      v-if="recentEvents.length === 0 && !loading"
      class="empty"
    >
      暂无审计记录
    </div>

    <div
      v-if="recentEvents.length > 0"
      class="audit-table-wrap"
    >
      <table class="audit-table">
        <thead>
          <tr>
            <th style="width: 150px">时间</th>
            <th>用户</th>
            <th>目标平台</th>
            <th>敏感类型</th>
            <th>风险</th>
            <th>用户操作</th>
            <th style="width: 50px"></th>
          </tr>
        </thead>
        <tbody>
          <template
            v-for="e in recentEvents"
            :key="e.eventId"
          >
            <tr
              @click="
                expandedRow === e.eventId
                  ? (expandedRow = null)
                  : (expandedRow = e.eventId)
              "
              class="clickable"
            >
              <td class="cell-time">{{ formatTime(e.timestamp) }}</td>
              <td>{{ e.userId || "-" }}</td>
              <td>
                <span class="provider-tag">{{
                  e.targetProvider || e.targetModel || "-"
                }}</span>
              </td>
              <td>
                <span
                  v-for="t in e.matchedSensitiveTypes || []"
                  :key="t"
                  class="type-badge"
                  >{{ t }}</span
                >
                <span
                  v-if="!e.matchedSensitiveTypes?.length"
                  class="muted"
                  >-</span
                >
              </td>
              <td>
                <span
                  v-if="e.inputRiskLevel === 'HIGH'"
                  class="risk-high"
                  >高</span
                >
                <span
                  v-else-if="e.inputRiskLevel === 'MEDIUM'"
                  class="risk-medium"
                  >中</span
                >
                <span
                  v-else-if="e.inputRiskLevel === 'LOW'"
                  class="risk-low"
                  >低</span
                >
                <span
                  v-else
                  class="muted"
                  >-</span
                >
              </td>
              <td>{{ USER_ACTION_LABELS[e.userAction] || "-" }}</td>
              <td>
                <span class="expand-arrow">{{
                  expandedRow === e.eventId ? "▼" : "▶"
                }}</span>
              </td>
            </tr>
            <tr
              v-if="expandedRow === e.eventId"
              class="expand-row"
            >
              <td colspan="7">
                <div class="expand-content">
                  <div class="content-pair">
                    <div class="content-box">
                      <div class="content-label">原始输入</div>
                      <div class="content-text">
                        {{ e.originalContent || "(未记录)" }}
                      </div>
                    </div>
                    <div class="content-box">
                      <div class="content-label">脱敏后</div>
                      <div class="content-text">
                        {{ e.processedContent || "(未记录)" }}
                      </div>
                    </div>
                  </div>
                  <div class="content-meta">
                    <span>部门: {{ e.department || "-" }}</span>
                    <span
                      >渠道:
                      {{ CHANNEL_LABELS[e.channel] || e.channel || "-" }}</span
                    >
                    <span>决策: {{ e.decisionAction || "-" }}</span>
                  </div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
  .gw-dashboard {
    width: 100%;
  }
  .page-head {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    margin-bottom: 20px;
  }
  .page-head h2 {
    margin: 0;
    font-size: 1.3rem;
  }
  .refresh-hint {
    font-size: 0.78rem;
    color: #94a3b8;
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
  .stat-card.danger {
    background: #fef2f2;
    border-color: #fecaca;
  }
  .stat-card.danger .value {
    color: #dc2626;
  }
  .stat-card.block {
    background: #fefce8;
    border-color: #fde047;
  }
  .stat-card.block .value {
    color: #ca8a04;
  }

  .charts-row {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
    margin-bottom: 24px;
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
    margin-bottom: 8px;
    font-weight: 600;
  }

  .section-title {
    font-size: 1rem;
    font-weight: 600;
    color: #334155;
    margin-bottom: 12px;
  }
  .empty {
    text-align: center;
    color: #94a3b8;
    padding: 30px 0;
  }

  .audit-table-wrap {
    overflow-x: auto;
  }
  .audit-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.85rem;
  }
  .audit-table th {
    text-align: left;
    padding: 10px 10px;
    background: #f8fafc;
    color: #64748b;
    font-weight: 600;
    border-bottom: 2px solid #e2e8f0;
  }
  .audit-table td {
    padding: 10px 10px;
    border-bottom: 1px solid #f1f5f9;
    vertical-align: middle;
  }
  .clickable {
    cursor: pointer;
  }
  .clickable:hover {
    background: #f8fafc;
  }
  .cell-time {
    font-size: 0.8rem;
    color: #64748b;
    white-space: nowrap;
  }

  .type-badge {
    display: inline-block;
    padding: 2px 7px;
    margin: 1px 2px;
    font-size: 0.72rem;
    background: #dbeafe;
    color: #1e40af;
    border-radius: 4px;
  }
  .risk-high {
    color: #dc2626;
    font-weight: 700;
  }
  .risk-medium {
    color: #d97706;
    font-weight: 600;
  }
  .risk-low {
    color: #2563eb;
  }
  .muted {
    color: #94a3b8;
  }
  .channel-tag {
    font-size: 0.78rem;
    color: #6366f1;
  }
  .provider-tag {
    font-size: 0.8rem;
    color: #7c3aed;
    font-weight: 500;
  }
  .expand-arrow {
    color: #94a3b8;
    font-size: 0.7rem;
  }

  .expand-row td {
    padding: 0;
    background: #fafbfc;
    border-bottom: 2px solid #e2e8f0;
  }
  .expand-content {
    padding: 14px 20px;
  }
  .content-pair {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 14px;
    margin-bottom: 10px;
  }
  .content-box {
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 8px;
    padding: 12px;
  }
  .content-label {
    font-size: 0.75rem;
    color: #94a3b8;
    margin-bottom: 6px;
    font-weight: 600;
    text-transform: uppercase;
  }
  .content-text {
    font-size: 0.84rem;
    color: #1e293b;
    line-height: 1.55;
    word-break: break-all;
    max-height: 120px;
    overflow-y: auto;
    white-space: pre-wrap;
  }
  .content-meta {
    display: flex;
    gap: 20px;
    font-size: 0.78rem;
    color: #64748b;
  }
  .gw-error {
    color: #ef4444;
    padding: 12px;
    background: #fef2f2;
    border-radius: 8px;
    margin-bottom: 12px;
  }
</style>
