<script setup>
import { ref, onMounted } from 'vue'
import { API_BASE_URL } from '../config'

const props = defineProps({
  eventId: { type: String, required: true },
})
const emit = defineEmits(['back'])

const event = ref(null)
const loading = ref(false)
const error = ref('')

const USER_ACTION_LABELS = {
  DESENSITIZE_AND_SEND: '发送脱敏版', SEND_ORIGINAL: '发送原文', CANCEL: '取消', AUTO: '自动处理',
}
const CHANNEL_LABELS = { BROWSER_PLUGIN: '浏览器插件', 'backend-api': 'API 调用' }

async function loadDetail() {
  loading.value = true
  error.value = ''
  try {
    const res = await fetch(`${API_BASE_URL}/gateway/v1/audit/events/${props.eventId}`)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const body = await res.json()
    event.value = body || null
  } catch (e) {
    error.value = '加载失败: ' + e.message
  } finally {
    loading.value = false
  }
}

onMounted(loadDetail)

function formatTime(ts) {
  if (!ts) return '-'
  const d = Array.isArray(ts) ? new Date(ts[0], ts[1] - 1, ts[2], ts[3] || 0, ts[4] || 0) : new Date(ts)
  return d.toLocaleString('zh-CN')
}
function riskColor(level) {
  const m = { HIGH: '#ef4444', MEDIUM: '#f59e0b', LOW: '#2563eb', NONE: '#94a3b8', CRITICAL: '#7c3aed' }
  return m[level] || '#94a3b8'
}
</script>

<template>
  <div class="detail-page">
    <div class="detail-head">
      <button class="btn-back" @click="$emit('back')">← 返回列表</button>
      <h2>审计事件详情</h2>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="error" class="gw-error">{{ error }}</div>
    <div v-else-if="!event" class="empty">事件不存在</div>

    <div v-else class="detail-body">
      <!-- 基本信息卡片 -->
      <div class="info-card">
        <div class="card-title">基本信息</div>
        <div class="info-grid">
          <div><span class="k">事件ID</span><span class="v code">{{ event.eventId }}</span></div>
          <div><span class="k">时间</span><span class="v">{{ formatTime(event.timestamp) }}</span></div>
          <div><span class="k">用户</span><span class="v">{{ event.userId || '-' }}</span></div>
          <div><span class="k">部门</span><span class="v">{{ event.department || '-' }}</span></div>
          <div><span class="k">渠道</span><span class="v">{{ CHANNEL_LABELS[event.channel] || event.channel || '-' }}</span></div>
          <div><span class="k">请求类型</span><span class="v">{{ event.requestType || '-' }}</span></div>
        </div>
      </div>

      <!-- 风险信息 -->
      <div class="info-card">
        <div class="card-title">风险与决策</div>
        <div class="risk-row">
          <div class="risk-item">
            <div class="kl">输入风险等级</div>
            <div class="vl" :style="{ color: riskColor(event.inputRiskLevel) }">{{ {HIGH:'高',MEDIUM:'中',LOW:'低',NONE:'无',CRITICAL:'严重'}[event.inputRiskLevel] || event.inputRiskLevel || '-' }}</div>
          </div>
          <div class="risk-item">
            <div class="kl">输出风险等级</div>
            <div class="vl" :style="{ color: riskColor(event.outputRiskLevel) }">{{ {HIGH:'高',MEDIUM:'中',LOW:'低',NONE:'无',CRITICAL:'严重'}[event.outputRiskLevel] || event.outputRiskLevel || '-' }}</div>
          </div>
          <div class="risk-item">
            <div class="kl">决策动作</div>
            <div class="vl">{{ event.decisionAction || '-' }}</div>
          </div>
          <div class="risk-item">
            <div class="kl">用户操作</div>
            <div class="vl">{{ USER_ACTION_LABELS[event.userAction] || event.userAction || '-' }}</div>
          </div>
        </div>
        <div class="types-line" v-if="event.matchedSensitiveTypes?.length">
          <span class="kl">命中敏感类型: </span>
          <span v-for="t in event.matchedSensitiveTypes" :key="t" class="type-badge">{{ t }}</span>
        </div>
      </div>

      <!-- 内容审查 -->
      <div class="info-card">
        <div class="card-title">内容审查</div>
        <div class="content-pair">
          <div class="content-box">
            <div class="cb-label">原始输入</div>
            <pre class="cb-text">{{ event.originalContent || '(未记录)' }}</pre>
          </div>
          <div class="content-box">
            <div class="cb-label">脱敏后</div>
            <pre class="cb-text">{{ event.processedContent || '(未记录)' }}</pre>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail-page { width: 100%; }
.detail-head { display: flex; align-items: center; gap: 16px; margin-bottom: 20px; }
.detail-head h2 { margin: 0; font-size: 1.3rem; }
.btn-back { padding: 6px 14px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; cursor: pointer; font-size: 0.85rem; }
.btn-back:hover { background: #f8fafc; }
.loading, .empty { text-align: center; color: #94a3b8; padding: 30px 0; }
.gw-error { color: #ef4444; padding: 12px; background: #fef2f2; border-radius: 8px; margin-bottom: 12px; }

.info-card { background: #fff; border: 1px solid #e2e8f0; border-radius: 10px; padding: 18px 20px; margin-bottom: 14px; }
.card-title { font-size: 0.85rem; color: #64748b; font-weight: 600; margin-bottom: 12px; text-transform: uppercase; }

.info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 24px; }
.info-grid .k { font-size: 0.78rem; color: #94a3b8; display: block; }
.info-grid .v { font-size: 0.88rem; color: #1e293b; }
.info-grid .code { font-family: monospace; font-size: 0.78rem; color: #6366f1; }

.risk-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 12px; }
.risk-item .kl { font-size: 0.75rem; color: #94a3b8; margin-bottom: 4px; }
.risk-item .vl { font-size: 0.95rem; font-weight: 700; }
.types-line { font-size: 0.85rem; }
.type-badge { display: inline-block; padding: 2px 7px; margin: 1px 2px; font-size: 0.72rem; background: #dbeafe; color: #1e40af; border-radius: 4px; }

.content-pair { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.content-box { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px; }
.cb-label { font-size: 0.75rem; color: #94a3b8; margin-bottom: 8px; font-weight: 600; }
.cb-text { font-size: 0.84rem; color: #1e293b; line-height: 1.6; white-space: pre-wrap; word-break: break-all; max-height: 200px; overflow-y: auto; margin: 0; font-family: inherit; }
</style>
