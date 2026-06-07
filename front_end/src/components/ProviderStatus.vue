<script setup>
import { ref, onMounted } from 'vue'
import { API_BASE_URL } from '../config'

const providers = ref([])
const loading = ref(false)
const error = ref('')

async function loadProviders() {
  loading.value = true
  error.value = ''
  try {
    const res = await fetch(`${API_BASE_URL}/gateway/v1/providers`)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    providers.value = await res.json()
  } catch (e) {
    error.value = '加载失败: ' + e.message
  } finally {
    loading.value = false
  }
}

onMounted(loadProviders)

function statusClass(s) {
  return s === 'available' ? 'status-online' : 'status-offline'
}
function statusLabel(s) {
  return s === 'available' ? '已配置' : '未配置'
}
</script>

<template>
  <div class="provider-page">
    <div class="page-head">
      <h2>LLM 供应商</h2>
      <button class="btn-refresh" @click="loadProviders" :disabled="loading">{{ loading ? '检测中...' : '刷新' }}</button>
    </div>

    <div v-if="error" class="gw-error">{{ error }}</div>

    <div class="provider-grid">
      <div v-for="p in providers" :key="p.code" class="provider-card">
        <div class="p-name">
          {{ p.name }}
          <span :class="['p-status', statusClass(p.status)]">{{ statusLabel(p.status) }}</span>
        </div>
        <div class="p-endpoint">{{ p.endpoint }}</div>
        <div class="p-code">{{ p.code }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.provider-page { width: 100%; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 1.3rem; }
.btn-refresh { padding: 6px 16px; border: 1px solid #e2e8f0; border-radius: 8px; background: #f8fafc; cursor: pointer; font-size: 0.85rem; }
.btn-refresh:disabled { opacity: 0.5; }
.gw-error { color: #ef4444; padding: 12px; background: #fef2f2; border-radius: 8px; margin-bottom: 12px; }

.provider-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
@media (max-width: 900px) { .provider-grid { grid-template-columns: repeat(2, 1fr); } }
.provider-card { border: 1px solid #e2e8f0; border-radius: 10px; padding: 16px; background: #fff; }
.p-name { font-size: 0.95rem; font-weight: 600; color: #1e293b; margin-bottom: 8px; display: flex; align-items: center; gap: 8px; }
.p-status { font-size: 0.7rem; padding: 2px 8px; border-radius: 10px; font-weight: 500; }
.status-online { background: #dcfce7; color: #16a34a; }
.status-offline { background: #f1f5f9; color: #94a3b8; }
.p-endpoint { font-size: 0.75rem; color: #94a3b8; margin-bottom: 4px; word-break: break-all; }
.p-code { font-size: 0.7rem; color: #cbd5e1; }
</style>
