<script setup>
import { ref, onMounted } from 'vue'
import { API_BASE_URL } from '../config'

const dictType = ref('SURNAME_WHITELIST')
const entries = ref([])
const newTerm = ref('')
const loading = ref(false)
const message = ref('')

const DICT_TYPES = [
  { value: 'SURNAME_WHITELIST', label: '姓氏白名单' },
  { value: 'PERSON_BLACKLIST', label: '人名黑名单' },
  { value: 'ADDRESS_BLACKLIST', label: '地址黑名单' },
  { value: 'ADDRESS_SUFFIXES', label: '地址后缀' },
]

async function loadEntries() {
  loading.value = true
  message.value = ''
  try {
    const res = await fetch(`${API_BASE_URL}/dict?dictType=${dictType.value}`)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    entries.value = await res.json()
  } catch (e) {
    message.value = '加载失败: ' + e.message
  } finally {
    loading.value = false
  }
}

async function addEntry() {
  const term = newTerm.value.trim()
  if (!term) { message.value = '请输入词条内容'; return }
  try {
    const res = await fetch(`${API_BASE_URL}/dict`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dictType: dictType.value, term, isEnabled: true }),
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    newTerm.value = ''
    message.value = `已添加词条: ${term}`
    await loadEntries()
  } catch (e) {
    message.value = '添加失败: ' + e.message
  }
}

async function deleteEntry(id, term) {
  if (!confirm(`确认删除词条 "${term}" 吗？`)) return
  try {
    await fetch(`${API_BASE_URL}/dict/${id}`, { method: 'DELETE' })
    message.value = `已删除: ${term}`
    await loadEntries()
  } catch (e) {
    message.value = '删除失败: ' + e.message
  }
}

async function reloadAll() {
  try {
    const res = await fetch(`${API_BASE_URL}/dict/reload`, { method: 'POST' })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    message.value = '词典已热重载'
  } catch (e) {
    message.value = '重载失败: ' + e.message
  }
}

onMounted(loadEntries)
</script>

<template>
  <div class="dict-page">
    <div class="page-head">
      <h2>词典管理</h2>
      <button class="btn-reload" @click="reloadAll">热重载全部词典</button>
    </div>

    <div class="type-tabs">
      <button v-for="t in DICT_TYPES" :key="t.value" :class="['tab', { active: dictType === t.value }]"
        @click="dictType = t.value; loadEntries()">{{ t.label }}</button>
    </div>

    <div class="add-row">
      <input class="add-inp" v-model="newTerm" placeholder="输入词条，按回车添加..."
        @keydown.enter="addEntry" />
      <button class="btn-add" @click="addEntry" :disabled="!newTerm.trim()">添加</button>
    </div>

    <div v-if="message" class="msg" :class="{ error: message.includes('失败') }">{{ message }}</div>

    <div v-if="entries.length === 0 && !loading" class="empty">该类型暂无词条</div>

    <div v-if="entries.length > 0" class="table-wrap">
      <table class="dict-table">
        <thead>
          <tr><th>ID</th><th>词条</th><th>类型</th><th style="width:80px">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="e in entries" :key="e.id">
            <td class="muted">{{ e.id }}</td>
            <td><code>{{ e.term }}</code></td>
            <td><span class="type-tag">{{ DICT_TYPES.find(t => t.value === e.dictType)?.label || e.dictType }}</span></td>
            <td><button class="btn-del" @click="deleteEntry(e.id, e.term)">删除</button></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.dict-page { width: 100%; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 1.3rem; }
.btn-reload { padding: 6px 16px; border: 1px solid #6366f1; color: #6366f1; background: #eef2ff; border-radius: 8px; cursor: pointer; font-size: 0.85rem; }

.type-tabs { display: flex; gap: 6px; margin-bottom: 14px; }
.tab { padding: 6px 16px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; cursor: pointer; font-size: 0.85rem; }
.tab.active { background: #6366f1; color: #fff; border-color: #6366f1; }

.add-row { display: flex; gap: 8px; margin-bottom: 12px; }
.add-inp { flex: 1; padding: 8px 12px; border: 1px solid #e2e8f0; border-radius: 8px; font-size: 0.9rem; }
.btn-add { padding: 8px 20px; background: #6366f1; color: #fff; border: none; border-radius: 8px; cursor: pointer; font-size: 0.9rem; }
.btn-add:disabled { opacity: 0.4; cursor: not-allowed; }

.msg { padding: 8px 12px; border-radius: 6px; background: #ecfdf5; color: #059669; font-size: 0.85rem; margin-bottom: 10px; }
.msg.error { background: #fef2f2; color: #dc2626; }
.empty { text-align: center; color: #94a3b8; padding: 30px 0; }

.table-wrap { overflow-x: auto; }
.dict-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
.dict-table th { text-align: left; padding: 10px 12px; background: #f8fafc; color: #64748b; font-weight: 600; border-bottom: 2px solid #e2e8f0; }
.dict-table td { padding: 10px 12px; border-bottom: 1px solid #f1f5f9; }
.muted { color: #94a3b8; font-size: 0.78rem; }
code { background: #f1f5f9; padding: 2px 6px; border-radius: 4px; font-size: 0.82rem; }
.type-tag { font-size: 0.75rem; color: #6366f1; background: #eef2ff; padding: 2px 7px; border-radius: 4px; }
.btn-del { padding: 4px 10px; border: 1px solid #fecaca; color: #dc2626; background: #fff; border-radius: 6px; cursor: pointer; font-size: 0.8rem; }
.btn-del:hover { background: #fef2f2; }
</style>
