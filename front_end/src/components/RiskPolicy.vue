<script setup>
  import { ref, computed } from "vue";

  const SENSITIVE_TYPE_OPTIONS = [
    "PHONE_NUMBER",
    "BANK_CARD",
    "ID_CARD",
    "EMAIL",
    "ADDRESS",
    "PERSON_NAME",
    "PASSWORD",
    "API_KEY",
    "LICENSE_PLATE",
    "CERTIFICATE_NUMBER",
    "COMPANY_NAME",
    "MEDICAL_DATA",
  ];
  const RISK_LEVELS = ["LOW", "MEDIUM", "HIGH"];
  const ACTIONS = [
    "ALLOW",
    "DESENSITIZE_AND_ALLOW",
    "BLOCK",
    "ROUTE_TO_INTERNAL_MODEL",
  ];
  const ACTION_LABELS = {
    ALLOW: "放行",
    DESENSITIZE_AND_ALLOW: "脱敏后放行",
    BLOCK: "阻断",
    ROUTE_TO_INTERNAL_MODEL: "路由到内部模型",
  };
  const RISK_LABELS = { LOW: "低", MEDIUM: "中", HIGH: "高" };

  const policies = ref([
    {
      id: 1,
      sceneName: "客服场景",
      types: ["PHONE_NUMBER", "ADDRESS", "ID_CARD"],
      threshold: 1,
      riskLevel: "MEDIUM",
      action: "DESENSITIZE_AND_ALLOW",
      enabled: true,
    },
    {
      id: 2,
      sceneName: "金融场景",
      types: ["BANK_CARD", "ID_CARD", "PASSWORD"],
      threshold: 0,
      riskLevel: "HIGH",
      action: "BLOCK",
      enabled: true,
    },
    {
      id: 3,
      sceneName: "医疗场景",
      types: ["ID_CARD", "MEDICAL_DATA", "PERSON_NAME"],
      threshold: 0,
      riskLevel: "HIGH",
      action: "BLOCK",
      enabled: true,
    },
    {
      id: 4,
      sceneName: "研发场景",
      types: ["API_KEY", "PASSWORD"],
      threshold: 1,
      riskLevel: "HIGH",
      action: "BLOCK",
      enabled: true,
    },
    {
      id: 5,
      sceneName: "招聘场景",
      types: ["PHONE_NUMBER", "ID_CARD"],
      threshold: 2,
      riskLevel: "MEDIUM",
      action: "DESENSITIZE_AND_ALLOW",
      enabled: true,
    },
  ]);
  const globalPolicy = ref({
    defaultAction: "DESENSITIZE_AND_ALLOW",
    maxSensitiveCount: 5,
    requireOutputReview: false,
  });
  const message = ref("");
  const editIdx = ref(-1);
  const newScene = ref({
    sceneName: "",
    types: [],
    threshold: 1,
    riskLevel: "MEDIUM",
    action: "DESENSITIZE_AND_ALLOW",
    enabled: true,
  });
  let nextId = 6;

  function togglePolicy(id) {
    const p = policies.value.find((r) => r.id === id);
    if (p) {
      p.enabled = !p.enabled;
      message.value = `策略已${p.enabled ? "启用" : "禁用"}`;
    }
  }
  function removePolicy(id) {
    policies.value = policies.value.filter((r) => r.id !== id);
    message.value = "策略已删除";
  }
  function startEdit(idx) {
    editIdx.value = idx;
  }
  function cancelEdit() {
    editIdx.value = -1;
  }
  function toggleType(policy, type) {
    const idx = policy.types.indexOf(type);
    if (idx >= 0) policy.types.splice(idx, 1);
    else policy.types.push(type);
    message.value = "";
  }

  function addNewScene() {
    if (!newScene.value.sceneName.trim()) {
      message.value = "请输入场景名称";
      return;
    }
    if (newScene.value.types.length === 0) {
      message.value = "请至少选择一种敏感类型";
      return;
    }
    policies.value.push({
      ...newScene.value,
      id: nextId++,
      types: [...newScene.value.types],
      sceneName: newScene.value.sceneName.trim(),
    });
    newScene.value = {
      sceneName: "",
      types: [],
      threshold: 1,
      riskLevel: "MEDIUM",
      action: "DESENSITIZE_AND_ALLOW",
      enabled: true,
    };
    message.value = "新场景已添加";
  }

  function toggleNewType(type) {
    const idx = newScene.value.types.indexOf(type);
    if (idx >= 0) newScene.value.types.splice(idx, 1);
    else newScene.value.types.push(type);
  }
</script>

<template>
  <div class="policy-page">
    <div class="page-head"><h2>风险策略配置</h2></div>
    <div
      v-if="message"
      class="msg"
      :class="{ error: message.includes('失败') }"
    >
      {{ message }}
    </div>

    <!-- 全局默认 -->
    <div class="section-title">全局默认</div>
    <div class="global-card">
      <div class="global-row">
        <span>默认决策动作</span>
        <select
          v-model="globalPolicy.defaultAction"
          class="sel"
        >
          <option
            v-for="a in ACTIONS"
            :key="a"
            :value="a"
          >
            {{ ACTION_LABELS[a] }}
          </option>
        </select>
      </div>
      <div class="global-row">
        <span>最大敏感信息数（超过则阻断）</span>
        <input
          type="number"
          v-model.number="globalPolicy.maxSensitiveCount"
          min="1"
          max="20"
          class="num-inp"
        />
      </div>
      <div class="global-row">
        <span>输出审查</span>
        <label class="toggle-label"
          ><input
            type="checkbox"
            v-model="globalPolicy.requireOutputReview"
          />
          启用</label
        >
      </div>
    </div>

    <!-- 场景策略矩阵 -->
    <div class="section-title">
      场景策略矩阵 <span class="count">({{ policies.length }}条)</span>
    </div>
    <div class="table-wrap">
      <table class="policy-table">
        <thead>
          <tr>
            <th>场景</th>
            <th>敏感类型</th>
            <th>阈值</th>
            <th>风险</th>
            <th>动作</th>
            <th style="width: 50px">启用</th>
            <th style="width: 60px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(p, idx) in policies"
            :key="p.id"
            :class="{ disabled: !p.enabled }"
          >
            <!-- 编辑模式 -->
            <template v-if="editIdx === idx">
              <td>
                <input
                  class="cell-inp"
                  v-model="p.sceneName"
                />
              </td>
              <td>
                <div class="type-chips">
                  <span
                    v-for="t in SENSITIVE_TYPE_OPTIONS"
                    :key="t"
                    :class="['chip', { selected: p.types.includes(t) }]"
                    @click="toggleType(p, t)"
                    >{{ t }}</span
                  >
                </div>
              </td>
              <td>
                <select
                  v-model.number="p.threshold"
                  class="sel-sm"
                >
                  <option
                    v-for="n in 5"
                    :key="n"
                    :value="n"
                  >
                    {{ n === 0 ? "任意" : n + "条" }}
                  </option>
                </select>
              </td>
              <td>
                <select
                  v-model="p.riskLevel"
                  class="sel-sm"
                >
                  <option
                    v-for="l in RISK_LEVELS"
                    :key="l"
                    :value="l"
                  >
                    {{ RISK_LABELS[l] }}
                  </option>
                </select>
              </td>
              <td>
                <select
                  v-model="p.action"
                  class="sel-sm"
                >
                  <option
                    v-for="a in ACTIONS"
                    :key="a"
                    :value="a"
                  >
                    {{ ACTION_LABELS[a] }}
                  </option>
                </select>
              </td>
              <td>
                <label class="toggle"
                  ><input
                    type="checkbox"
                    :checked="p.enabled"
                    @change="togglePolicy(p.id)" /><span
                    class="toggle-slider"
                  ></span
                ></label>
              </td>
              <td>
                <button
                  class="btn-sm btn-save"
                  @click="
                    editIdx = -1;
                    message = '策略已保存';
                  "
                >
                  保存
                </button>
              </td>
            </template>
            <!-- 展示模式 -->
            <template v-else>
              <td class="scene-name">{{ p.sceneName }}</td>
              <td>
                <span
                  v-for="t in p.types"
                  :key="t"
                  class="type-badge"
                  >{{ t }}</span
                >
              </td>
              <td>{{ p.threshold === 0 ? "任意" : p.threshold + "条" }}</td>
              <td>
                <span
                  :style="{
                    color: {
                      LOW: '#2563eb',
                      MEDIUM: '#f59e0b',
                      HIGH: '#ef4444',
                    }[p.riskLevel],
                    fontWeight: 700,
                  }"
                  >{{ RISK_LABELS[p.riskLevel] }}</span
                >
              </td>
              <td>{{ ACTION_LABELS[p.action] }}</td>
              <td>
                <label class="toggle"
                  ><input
                    type="checkbox"
                    :checked="p.enabled"
                    @change="togglePolicy(p.id)" /><span
                    class="toggle-slider"
                  ></span
                ></label>
              </td>
              <td>
                <button
                  class="btn-sm btn-edit"
                  @click="startEdit(idx)"
                >
                  编辑
                </button>
                <button
                  class="btn-sm btn-del"
                  @click="removePolicy(p.id)"
                >
                  删除
                </button>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 新增场景 -->
    <div
      class="section-title"
      style="margin-top: 20px"
    >
      新增场景策略
    </div>
    <div class="add-card">
      <div class="add-row">
        <span class="add-label">场景名称</span>
        <input
          class="cell-inp"
          v-model="newScene.sceneName"
          placeholder="如：法律场景"
        />
      </div>
      <div class="add-row">
        <span class="add-label">敏感类型</span>
        <div class="type-chips">
          <span
            v-for="t in SENSITIVE_TYPE_OPTIONS"
            :key="t"
            :class="['chip', { selected: newScene.types.includes(t) }]"
            @click="toggleNewType(t)"
            >{{ t }}</span
          >
        </div>
      </div>
      <div class="add-row">
        <span class="add-label">阈值</span>
        <select
          v-model.number="newScene.threshold"
          class="sel-sm"
        >
          <option
            v-for="n in 5"
            :key="n"
            :value="n"
          >
            {{ n === 0 ? "任意" : n + "条" }}
          </option>
        </select>
        <span class="add-label">风险</span>
        <select
          v-model="newScene.riskLevel"
          class="sel-sm"
        >
          <option
            v-for="l in RISK_LEVELS"
            :key="l"
            :value="l"
          >
            {{ RISK_LABELS[l] }}
          </option>
        </select>
        <span class="add-label">动作</span>
        <select
          v-model="newScene.action"
          class="sel-sm"
        >
          <option
            v-for="a in ACTIONS"
            :key="a"
            :value="a"
          >
            {{ ACTION_LABELS[a] }}
          </option>
        </select>
        <button
          class="btn-add"
          @click="addNewScene"
        >
          添加场景
        </button>
      </div>
    </div>

    <div class="note">
      策略在每次网关调用时实时生效。修改后将影响后续所有请求。
    </div>
  </div>
</template>

<style scoped>
  .policy-page {
    width: 100%;
  }
  .page-head {
    margin-bottom: 16px;
  }
  .page-head h2 {
    margin: 0;
    font-size: 1.3rem;
  }
  .msg {
    padding: 8px 12px;
    border-radius: 6px;
    background: #ecfdf5;
    color: #059669;
    font-size: 0.85rem;
    margin-bottom: 10px;
  }
  .msg.error {
    background: #fef2f2;
    color: #dc2626;
  }
  .section-title {
    font-size: 0.9rem;
    font-weight: 600;
    color: #64748b;
    margin-bottom: 10px;
    margin-top: 18px;
  }
  .count {
    font-weight: 400;
    color: #94a3b8;
  }

  .global-card {
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    padding: 16px;
  }
  .global-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 6px 0;
    font-size: 0.88rem;
    color: #334155;
  }

  .table-wrap {
    overflow-x: auto;
    margin-bottom: 6px;
  }
  .policy-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.84rem;
  }
  .policy-table th {
    text-align: left;
    padding: 10px 10px;
    background: #f8fafc;
    color: #64748b;
    font-weight: 600;
    border-bottom: 2px solid #e2e8f0;
    white-space: nowrap;
  }
  .policy-table td {
    padding: 8px 10px;
    border-bottom: 1px solid #f1f5f9;
    vertical-align: middle;
  }
  tr.disabled td {
    opacity: 0.45;
  }
  .scene-name {
    font-weight: 600;
    color: #1e293b;
  }

  .type-badge {
    display: inline-block;
    padding: 2px 7px;
    margin: 1px 2px;
    font-size: 0.7rem;
    background: #dbeafe;
    color: #1e40af;
    border-radius: 4px;
  }
  .type-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 3px;
  }
  .chip {
    padding: 2px 7px;
    font-size: 0.7rem;
    border: 1px solid #e2e8f0;
    border-radius: 4px;
    cursor: pointer;
    background: #fff;
    color: #94a3b8;
    user-select: none;
  }
  .chip.selected {
    background: #dbeafe;
    border-color: #93c5fd;
    color: #1e40af;
  }

  .cell-inp {
    padding: 4px 8px;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    font-size: 0.84rem;
    width: 130px;
  }
  .sel,
  .sel-sm {
    padding: 4px 8px;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    font-size: 0.84rem;
  }
  .num-inp {
    width: 60px;
    text-align: center;
    padding: 4px 8px;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    font-size: 0.85rem;
  }
  .toggle-label {
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 0.85rem;
  }

  .toggle {
    position: relative;
    display: inline-block;
    width: 36px;
    height: 20px;
  }
  .toggle input {
    opacity: 0;
    width: 0;
    height: 0;
  }
  .toggle-slider {
    position: absolute;
    cursor: pointer;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: #cbd5e1;
    border-radius: 20px;
    transition: 0.2s;
  }
  .toggle-slider::before {
    content: "";
    position: absolute;
    height: 14px;
    width: 14px;
    left: 3px;
    bottom: 3px;
    background: #fff;
    border-radius: 50%;
    transition: 0.2s;
  }
  .toggle input:checked + .toggle-slider {
    background: #6366f1;
  }
  .toggle input:checked + .toggle-slider::before {
    transform: translateX(16px);
  }

  .btn-sm {
    padding: 3px 10px;
    border-radius: 5px;
    font-size: 0.78rem;
    cursor: pointer;
    border: 1px solid;
    background: #fff;
  }
  .btn-save {
    color: #059669;
    border-color: #a7f3d0;
  }
  .btn-edit {
    color: #6366f1;
    border-color: #c7d2fe;
    margin-right: 4px;
  }
  .btn-del {
    color: #dc2626;
    border-color: #fecaca;
  }

  .add-card {
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    padding: 14px 16px;
  }
  .add-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 0;
    flex-wrap: wrap;
  }
  .add-label {
    font-size: 0.82rem;
    color: #64748b;
    min-width: 60px;
  }
  .btn-add {
    padding: 6px 18px;
    background: #6366f1;
    color: #fff;
    border: none;
    border-radius: 8px;
    cursor: pointer;
    font-size: 0.85rem;
    margin-left: auto;
  }
  .note {
    margin-top: 14px;
    font-size: 0.78rem;
    color: #94a3b8;
  }
</style>
