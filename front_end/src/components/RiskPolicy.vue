<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { API_BASE_URL } from '../config'

const providers = ref([])
const rulePolicies = ref([
  { id: 1, scene: '客服场景', types: '手机号, 地址, 身份证', threshold: '1条', riskLevel: 'MEDIUM', action: 'DESENSITIZE_AND_ALLOW', enabled: true },
  { id: 2, scene: '金融场景', types: '银行卡, 身份证, 密码', threshold: '任意', riskLevel: 'HIGH', action: 'BLOCK', enabled: true },
  { id: 3, scene: '医疗场景', types: '身份证, 病历号, 处方信息', threshold: '任意', riskLevel: 'HIGH', action: 'BLOCK', enabled: true },
  { id: 4, scene: '研发场景', types: 'API Key, 密码, 密钥', threshold: '1条', riskLevel: 'HIGH', action: 'BLOCK', enabled: true },
  { id: 5, scene: '招聘场景', types: '手机号, 身份证', threshold: '2条', riskLevel: 'MEDIUM', action: 'DESENSITIZE_AND_ALLOW', enabled: true },
  { id: 6, scene: '通用场景', types: '手机号, 邮箱, 地址', threshold: '3条', riskLevel: 'MEDIUM', action: 'DESENSITIZE_AND_ALLOW', enabled: true },
])

const globalPolicy = ref({ requireOutputReview: false, defaultAction: 'DESENSITIZE_AND_ALLOW', maxSensitiveCount: 5 })
const message = ref('')

function togglePolicy(id) {
  const p = rulePolicies.value.find(r => r.id === id)
  if (p) { p.enabled = !p.enabled; message.value = `策略已${p.enabled ? '启用' : '禁用'}` }
}

const RISK_COLORS = { HIGH: 'color:#ef4444', MEDIUM: 'color:#f59e0b', LOW: 'color:#2563eb' }
const ACTION_LABELS = { BLOCK: '阻断', DESENSITIZE_AND_ALLOW: '脱敏后放行', ALLOW: '直接放行', ROUTE_TO_INTERNAL_MODEL: '路由到内部模型' }
</script>

<template>
  <div class="policy-page">
    <div class="page-head"><h2>风险策略配置</h2></div>

    <div v-if="message" class="msg">{{ message }}</div>

    <!-- 全局默认策略 -->
    <div class="section-title">全局默认</div>
    <div class="global-card">
      <div class="global-row">
        <span>默认决策动作</span>
        <select v-model="globalPolicy.defaultAction" class="sel">
          <option value="ALLOW">直接放行</option>
          <option value="DESENSITIZE_AND_ALLOW">脱敏后放行</option>
          <option value="BLOCK">阻断</option>
        </select>
      </div>
      <div class="global-row">
        <span>最大敏感信息数（超过则阻断）</span>
        <input type="number" v-model.number="globalPolicy.maxSensitiveCount" min="1" max="20" class="num-inp" />
      </div>
      <div class="global-row">
        <span>输出审查</span>
        <label class="toggle-label"><input type="checkbox" v-model="globalPolicy.requireOutputReview" /> 启用</label>
      </div>
    </div>

    <!-- 场景策略矩阵 -->
    <div class="section-title">场景策略矩阵</div>
    <div class="table-wrap">
      <table class="policy-table">
        <thead>
          <tr>
            <th>业务场景</th>
            <th>敏感类型</th>
            <th>阈值</th>
            <th>风险等级</th>
            <th>决策动作</th>
            <th style="width:60px">启用</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in rulePolicies" :key="p.id" :class="{ disabled: !p.enabled }">
            <td class="scene-name">{{ p.scene }}</td>
            <td><span v-for="t in p.types.split(',').map(s=>s.trim())" :key="t" class="type-badge">{{ t }}</span></td>
            <td>{{ p.threshold }}</td>
            <td><span :style="RISK_COLORS[p.riskLevel]" class="risk-text">{{ {HIGH:'高',MEDIUM:'中',LOW:'低'}[p.riskLevel] }}</span></td>
            <td>{{ ACTION_LABELS[p.action] || p.action }}</td>
            <td>
              <label class="toggle">
                <input type="checkbox" :checked="p.enabled" @change="togglePolicy(p.id)" />
                <span class="toggle-slider"></span>
              </label>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="note">策略会在每次网关调用时实时生效。修改后将影响后续所有请求。</div>
  </div>
</template>

<style scoped>
.policy-page { width: 100%; }
.page-head { margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 1.3rem; }
.msg { padding: 8px 12px; border-radius: 6px; background: #ecfdf5; color: #059669; font-size: 0.85rem; margin-bottom: 10px; }

.section-title { font-size: 0.9rem; font-weight: 600; color: #64748b; margin-bottom: 10px; margin-top: 18px; }

.global-card { background: #fff; border: 1px solid #e2e8f0; border-radius: 10px; padding: 16px; }
.global-row { display: flex; align-items: center; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid #f1f5f9; font-size: 0.88rem; color: #334155; }
.global-row:last-child { border-bottom: none; }
.sel, .num-inp { padding: 4px 10px; border: 1px solid #e2e8f0; border-radius: 6px; font-size: 0.85rem; }
.num-inp { width: 60px; text-align: center; }
.toggle-label { cursor: pointer; display: flex; align-items: center; gap: 6px; font-size: 0.85rem; }

.table-wrap { overflow-x: auto; }
.policy-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
.policy-table th { text-align: left; padding: 10px 12px; background: #f8fafc; color: #64748b; font-weight: 600; border-bottom: 2px solid #e2e8f0; white-space: nowrap; }
.policy-table td { padding: 10px 12px; border-bottom: 1px solid #f1f5f9; }
tr.disabled td { opacity: 0.45; }
.scene-name { font-weight: 600; color: #1e293b; }

.type-badge { display: inline-block; padding: 2px 7px; margin: 1px 2px; font-size: 0.72rem; background: #dbeafe; color: #1e40af; border-radius: 4px; }
.risk-text { font-weight: 700; }

.toggle { position: relative; display: inline-block; width: 36px; height: 20px; }
.toggle input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background: #cbd5e1; border-radius: 20px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; height: 14px; width: 14px; left: 3px; bottom: 3px; background: #fff; border-radius: 50%; transition: 0.2s; }
.toggle input:checked + .toggle-slider { background: #6366f1; }
.toggle input:checked + .toggle-slider::before { transform: translateX(16px); }

.note { margin-top: 14px; font-size: 0.78rem; color: #94a3b8; }
</style>
