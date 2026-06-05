<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { API_BASE_URL } from '../config'

const events = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const filterUserId = ref('')

let refreshTimer = null

const USER_ACTION_LABELS = {
  DESENSITIZE_AND_SEND: '发送脱敏版',
  SEND_ORIGINAL: '发送原文',
  CANCEL: '取消',
  AUTO: '自动处理',
}

const CHANNEL_LABELS = {
  BROWSER_PLUGIN: '插件',
  'backend-api': 'API',
}

async function loadAuditEvents() {
  loading.value = true
  error.value = ''
  try {
    let url = `${API_BASE_URL}/gateway/v1/audit/events`
    const params = []
    if (filterUserId.value) params.push(`userId=${encodeURIComponent(filterUserId.value)}`)
    if (params.length) url += '?' + params.join('&')

    const res = await fetch(url)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const body = await res.json()
    if (body.data?.items) {
      events.value = body.data.items
      total.value = body.data.total
    }
  } catch (e) {
    error.value = '加载审计列表失败: ' + e.message
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadAuditEvents()
  refreshTimer = setInterval(loadAuditEvents, 10000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})

function formatTime(ts) {
  if (!ts) return '-'
  const d = new Date(isoToLocal(ts))
  return d.toLocaleString('zh-CN')
}

function isoToLocal(ts) {
  if (Array.isArray(ts)) {
    const [y, mo, d, h, mi, s, ns] = ts
    return new Date(y, mo - 1, d, h, mi, s, Math.floor(ns / 1000000)).toISOString()
  }
  return ts
}

function riskBadge(level) {
  if (!level) return ''
  const map = { HIGH: '高', MEDIUM: '中', LOW: '低', NONE: '-' }
  const cls = { HIGH: 'badge-danger', MEDIUM: 'badge-warn', LOW: 'badge-info', NONE: 'badge-muted' }
  return `<span class="badge ${cls[level] || 'badge-muted'}">${map[level] || level}</span>`
}
</script>

<template>
  <div class="audit-list">
    <div class="audit-header">
      <h2>审计事件</h2>
      <div class="audit-toolbar">
        <input
          class="filter-input"
          v-model="filterUserId"
          placeholder="按用户筛选..."
          @keydown.enter="loadAuditEvents"
        />
        <button class="btn-refresh" @click="loadAuditEvents" :disabled="loading">
          {{ loading ? '刷新中...' : '刷新' }}
        </button>
      </div>
    </div>

    <div v-if="error" class="audit-error">{{ error }}</div>

    <div v-if="events.length === 0 && !loading && !error" class="audit-empty">
      暂无审计记录
    </div>

    <div v-if="events.length > 0" class="audit-table-wrap">
      <table class="audit-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>用户</th>
            <th>部门</th>
            <th>渠道</th>
            <th>敏感类型</th>
            <th>风险</th>
            <th>决策</th>
            <th>用户操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in events" :key="e.eventId">
            <td class="cell-time">{{ formatTime(e.timestamp) }}</td>
            <td>{{ e.userId || '-' }}</td>
            <td>{{ e.department || '-' }}</td>
            <td>
              <span class="channel-tag" :data-channel="e.channel">
                {{ CHANNEL_LABELS[e.channel] || e.channel || '-' }}
              </span>
            </td>
            <td>
              <span v-if="e.matchedSensitiveTypes?.length" class="type-tags">
                <span v-for="t in e.matchedSensitiveTypes" :key="t" class="type-tag">{{ t }}</span>
              </span>
              <span v-else>-</span>
            </td>
            <td v-html="riskBadge(e.inputRiskLevel)"></td>
            <td>{{ e.decisionAction || '-' }}</td>
            <td>
              <span v-if="e.userAction" class="action-tag" :data-action="e.userAction">
                {{ e.userAction }}
              </span>
              <span v-else>-</span>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="audit-footer">共 {{ events.length }} 条</div>
    </div>
  </div>
</template>

<style scoped>
.audit-list {
  width: 100%;
}

.audit-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.audit-header h2 {
  font-size: 1.4rem;
  color: var(--text);
  margin: 0;
}

.audit-toolbar {
  display: flex;
  gap: 8px;
}

.filter-input {
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 0.9rem;
  background: var(--card-bg);
  color: var(--text);
  width: 160px;
}

.btn-refresh {
  padding: 6px 16px;
  background: #6366f1;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.9rem;
}

.btn-refresh:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.audit-error {
  padding: 12px;
  background: #fef2f2;
  color: #dc2626;
  border-radius: 6px;
  margin-bottom: 12px;
}

.audit-empty {
  padding: 40px;
  text-align: center;
  color: #94a3b8;
  font-size: 0.95rem;
}

.audit-table-wrap {
  overflow-x: auto;
}

.audit-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.88rem;
}

.audit-table th {
  text-align: left;
  padding: 10px 8px;
  border-bottom: 2px solid var(--border);
  color: #64748b;
  font-weight: 600;
  white-space: nowrap;
}

.audit-table td {
  padding: 8px;
  border-bottom: 1px solid var(--border);
  color: var(--text);
  vertical-align: middle;
}

.cell-time {
  white-space: nowrap;
  font-size: 0.82rem;
  color: #64748b;
}

.channel-tag {
  font-size: 0.78rem;
  padding: 2px 6px;
  border-radius: 4px;
  background: #e0e7ff;
  color: #4338ca;
}

.channel-tag[data-channel="backend-api"] {
  background: #fce7f3;
  color: #be185d;
}

.type-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
}

.type-tag {
  font-size: 0.72rem;
  padding: 1px 5px;
  border-radius: 3px;
  background: #f1f5f9;
  color: #475569;
}

.action-tag {
  font-size: 0.78rem;
  padding: 2px 6px;
  border-radius: 4px;
}

.action-tag[data-action="CANCEL"] {
  background: #fef2f2;
  color: #dc2626;
}

.action-tag[data-action="SEND_ORIGINAL"] {
  background: #fffbeb;
  color: #d97706;
}

.action-tag[data-action="DESENSITIZE_AND_SEND"] {
  background: #f0fdf4;
  color: #16a34a;
}

:deep(.badge) {
  font-size: 0.78rem;
  padding: 2px 6px;
  border-radius: 4px;
}

:deep(.badge-danger) { background: #fef2f2; color: #dc2626; }
:deep(.badge-warn) { background: #fffbeb; color: #d97706; }
:deep(.badge-info) { background: #f0fdf4; color: #16a34a; }
:deep(.badge-muted) { background: #f1f5f9; color: #94a3b8; }

.audit-footer {
  padding: 8px;
  text-align: right;
  color: #94a3b8;
  font-size: 0.82rem;
}
</style>
