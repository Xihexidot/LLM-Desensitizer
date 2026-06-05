<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { Chart as ChartJS, ArcElement, Tooltip, Legend } from 'chart.js'
import { Pie } from 'vue-chartjs'
import { API_BASE_URL } from '../config'

ChartJS.register(ArcElement, Tooltip, Legend)

const stats = ref({ todayTotal: 0, byChannel: [], byDecision: [] })
const loading = ref(false)
const error = ref('')
let refreshTimer = null

async function loadStats() {
  loading.value = true
  error.value = ''
  try {
    const res = await fetch(`${API_BASE_URL}/gateway/v1/audit/stats`)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const data = await res.json()
    stats.value = data
  } catch (e) {
    error.value = '加载失败: ' + e.message
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadStats()
  refreshTimer = setInterval(loadStats, 15000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})

function channelCount(name) {
  const item = stats.value.byChannel?.find(c => c.channel === name)
  return item?.cnt ?? 0
}

function decisionCount(name) {
  const item = stats.value.byDecision?.find(d => d.decision_action === name)
  return item?.cnt ?? 0
}

const totalIntercepted = ref(0)

const chartData = ref({
  labels: [],
  datasets: [{ backgroundColor: [], data: [] }]
})

const chartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: { legend: { position: 'right', labels: { usePointStyle: true, boxWidth: 10 } } }
}
</script>

<template>
  <div class="dashboard-page">
    <div class="dashboard-header">
      <h2>安全仪表盘</h2>
      <button class="btn-refresh" @click="loadStats" :disabled="loading">
        {{ loading ? '刷新中...' : '刷新' }}
      </button>
    </div>

    <div v-if="error" class="audit-error">{{ error }}</div>

    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-label">今日拦截</div>
        <div class="stat-value">{{ stats.todayTotal }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">插件检测</div>
        <div class="stat-value accent-blue">{{ channelCount('BROWSER_PLUGIN') }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">API 调用</div>
        <div class="stat-value accent-green">{{ channelCount('backend-api') }}</div>
      </div>
    </div>

    <div v-if="stats.todayTotal === 0 && !loading" class="audit-empty">
      暂无今日统计数据，开始使用插件或 API 即可产生数据。
    </div>
  </div>
</template>

<style scoped>
.dashboard-page { width: 100%; }
.dashboard-header {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 20px;
}
.dashboard-header h2 { margin: 0; font-size: 1.4rem; color: var(--text); }
.btn-refresh {
  padding: 8px 20px; border: 1px solid var(--border); border-radius: 8px;
  background: var(--btn-bg, #f1f5f9); color: var(--text); cursor: pointer;
  font-size: 0.9rem;
}
.btn-refresh:disabled { opacity: 0.5; cursor: not-allowed; }
.stats-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 24px; }
.stat-card {
  background: var(--card-bg); border: 1px solid var(--border);
  border-radius: 12px; padding: 20px; text-align: center;
}
.stat-label { font-size: 0.85rem; color: #64748b; margin-bottom: 8px; }
.stat-value { font-size: 2.5rem; font-weight: 800; color: #3b82f6; }
.stat-value.accent-blue { color: #6366f1; }
.stat-value.accent-green { color: #10b981; }
.audit-error { color: #ef4444; padding: 12px; background: #fef2f2; border-radius: 8px; margin-bottom: 12px; }
.audit-empty { text-align: center; color: #94a3b8; padding: 40px 0; font-size: 1rem; }
</style>
