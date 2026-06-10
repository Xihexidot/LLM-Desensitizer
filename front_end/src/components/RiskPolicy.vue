<script setup>
  import { ref, onMounted } from "vue";
  import { API_BASE_URL } from "../config";

  // ========== 所有敏感类型 ==========
  const ALL_TYPES = [
    "PHONE_NUMBER",
    "BANK_CARD",
    "ID_CARD",
    "EMAIL",
    "ADDRESS",
    "PERSON_NAME",
    "PASSWORD",
    "API_KEY",
    "LICENSE_PLATE",
    "PASSPORT",
    "SOCIAL_SECURITY",
    "CREDIT_CARD",
    "BIRTH_DATE",
    "IP_ADDRESS",
    "ORGANIZATION",
  ];
  const TYPE_LABELS = {
    PHONE_NUMBER: "手机号",
    BANK_CARD: "银行卡",
    ID_CARD: "身份证",
    EMAIL: "邮箱",
    ADDRESS: "地址",
    PERSON_NAME: "人名",
    PASSWORD: "密码",
    API_KEY: "API密钥",
    LICENSE_PLATE: "车牌",
    PASSPORT: "护照",
    SOCIAL_SECURITY: "社保号",
    CREDIT_CARD: "信用卡",
    BIRTH_DATE: "生日",
    IP_ADDRESS: "IP地址",
    ORGANIZATION: "机构名",
  };
  const RISK_LEVELS = ["LOW", "MEDIUM", "HIGH", "CRITICAL"];
  const RISK_LABELS = { LOW: "低", MEDIUM: "中", HIGH: "高", CRITICAL: "严重" };
  const ACTIONS = ["ALLOW", "DESENSITIZE_AND_ALLOW", "BLOCK"];
  const ACTION_LABELS = {
    ALLOW: "放行",
    DESENSITIZE_AND_ALLOW: "脱敏后放行",
    BLOCK: "阻断",
  };

  // ========== 风险策略 ==========
  const policies = ref([]);
  const globalPolicy = ref({
    defaultAction: "DESENSITIZE_AND_ALLOW",
    maxSensitiveCount: 5,
    requireOutputReview: false,
  });
  const message = ref("");
  const loading = ref(false);
  const editIdx = ref(-1);
  let nextId = 100;

  const newScene = ref({
    sceneName: "",
    types: [],
    detectTypes: [],
    threshold: 1,
    riskLevel: "MEDIUM",
    action: "DESENSITIZE_AND_ALLOW",
    enabled: true,
  });

  async function loadConfig() {
    loading.value = true;
    try {
      const res = await fetch(`${API_BASE_URL}/gateway/v1/risk-policy`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      if (data.global) globalPolicy.value = data.global;
      if (data.scenes)
        policies.value = data.scenes.map((s) => ({
          ...s,
          detectTypes: s.detectTypes || [],
          types: s.types || [],
        }));
      nextId = Math.max(100, ...policies.value.map((p) => p.id || 0)) + 1;
    } catch (e) {
      message.value = "加载失败: " + e.message;
    } finally {
      loading.value = false;
    }
  }
  async function saveConfig(msg) {
    try {
      const res = await fetch(`${API_BASE_URL}/gateway/v1/risk-policy`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          global: globalPolicy.value,
          scenes: policies.value,
        }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      message.value = msg || "已保存";
    } catch (e) {
      message.value = "保存失败: " + e.message;
    }
  }
  onMounted(loadConfig);

  function togglePolicy(id) {
    const p = policies.value.find((r) => r.id === id);
    if (p) {
      p.enabled = !p.enabled;
      saveConfig(`策略已${p.enabled ? "启用" : "禁用"}`);
    }
  }
  function removePolicy(id) {
    policies.value = policies.value.filter((r) => r.id !== id);
    saveConfig("策略已删除");
  }
  function startEdit(idx) {
    editIdx.value = idx;
  }
  function finishEdit(idx) {
    editIdx.value = -1;
    saveConfig("策略已保存");
  }
  function cancelEdit() {
    editIdx.value = -1;
  }
  function toggleArr(arr, val) {
    const i = arr.indexOf(val);
    i >= 0 ? arr.splice(i, 1) : arr.push(val);
  }
  function addNewScene() {
    if (!newScene.value.sceneName.trim()) {
      message.value = "请输入场景名称";
      return;
    }
    policies.value.push({
      ...newScene.value,
      id: nextId++,
      types: [...newScene.value.types],
      detectTypes: [...newScene.value.detectTypes],
      sceneName: newScene.value.sceneName.trim(),
    });
    newScene.value = {
      sceneName: "",
      types: [],
      detectTypes: [],
      threshold: 1,
      riskLevel: "MEDIUM",
      action: "DESENSITIZE_AND_ALLOW",
      enabled: true,
    };
    saveConfig("新场景已添加");
  }

  // ========== 词典管理 (内嵌) ==========
  const dictSection = ref(false);
  const dictType = ref("SURNAME_WHITELIST");
  const dictEntries = ref([]);
  const newTerm = ref("");
  const dictMsg = ref("");
  const DICT_TYPES = [
    { value: "SURNAME_WHITELIST", label: "姓氏白名单" },
    { value: "PERSON_BLACKLIST", label: "人名黑名单" },
    { value: "ADDRESS_BLACKLIST", label: "地址黑名单" },
    { value: "ADDRESS_SUFFIXES", label: "地址后缀" },
  ];
  async function loadDict() {
    dictMsg.value = "";
    try {
      const r = await fetch(`${API_BASE_URL}/dict?dictType=${dictType.value}`);
      if (r.ok) {
        dictEntries.value = await r.json();
      } else {
        dictMsg.value = `加载词典失败 (HTTP ${r.status})`;
      }
    } catch (e) {
      dictMsg.value = "加载词典失败: " + (e.message || "网络错误");
    }
  }
  async function addDictEntry() {
    const t = newTerm.value.trim();
    if (!t) {
      dictMsg.value = "请输入词条";
      return;
    }
    dictMsg.value = "";
    try {
      const r = await fetch(`${API_BASE_URL}/dict`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          dictType: dictType.value,
          term: t,
          isEnabled: true,
        }),
      });
      if (!r.ok) {
        dictMsg.value = `添加失败 (HTTP ${r.status})`;
        return;
      }
      newTerm.value = "";
      dictMsg.value = `已添加: ${t}`;
      await loadDict();
    } catch (e) {
      dictMsg.value = "添加失败: " + (e.message || "网络错误");
    }
  }
  async function delDictEntry(id, term) {
    if (!confirm(`删除"${term}"?`)) return;
    try {
      await fetch(`${API_BASE_URL}/dict/${id}`, { method: "DELETE" });
      dictMsg.value = `已删除: ${term}`;
      loadDict();
    } catch (e) {
      dictMsg.value = "删除失败: " + e.message;
    }
  }
  async function reloadDicts() {
    try {
      await fetch(`${API_BASE_URL}/dict/reload`, { method: "POST" });
      dictMsg.value = "词典已热重载";
    } catch (e) {
      dictMsg.value = "重载失败: " + e.message;
    }
  }

  // ========== 自定义正则 (内嵌) ==========
  const ruleSection = ref(false);
  const rules = ref([]);
  const ruleMsg = ref("");
  const newRule = ref({
    patternName: "",
    regex: "",
    description: "",
    isEnabled: true,
  });
  async function fetchRules() {
    try {
      const r = await fetch(`${API_BASE_URL}/rules`);
      if (r.ok) rules.value = await r.json();
    } catch (e) {}
  }
  async function addRule() {
    try {
      await fetch(`${API_BASE_URL}/rules`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(newRule.value),
      });
      ruleMsg.value = "规则已添加";
      newRule.value = {
        patternName: "",
        regex: "",
        description: "",
        isEnabled: true,
      };
      fetchRules();
    } catch (e) {
      ruleMsg.value = "添加失败: " + e.message;
    }
  }
  async function toggleRule(r) {
    r.isEnabled = !r.isEnabled;
    try {
      await fetch(`${API_BASE_URL}/rules/${r.patternName}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(r),
      });
    } catch (e) {}
  }
  async function deleteRule(name) {
    if (!confirm(`删除规则"${name}"?`)) return;
    try {
      await fetch(`${API_BASE_URL}/rules/${name}`, { method: "DELETE" });
      ruleMsg.value = "已删除";
      fetchRules();
    } catch (e) {
      ruleMsg.value = "删除失败: " + e.message;
    }
  }
</script>

<template>
  <div class="policy-page">
    <div class="page-head"><h2>风险策略配置中心</h2></div>
    <div
      v-if="loading"
      class="loading"
    >
      加载中...
    </div>
    <div
      v-if="message"
      class="msg"
      :class="{ error: message.includes('失败') }"
    >
      {{ message }}
    </div>

    <!-- ===== 一、全局默认 ===== -->
    <div class="section-title">全局默认</div>
    <div class="card">
      <div class="row">
        <span>默认决策动作</span>
        <select
          v-model="globalPolicy.defaultAction"
          @change="saveConfig('全局配置已保存')"
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
        <span style="margin-left: 20px">最大敏感条数（超则阻断）</span>
        <input
          type="number"
          v-model.number="globalPolicy.maxSensitiveCount"
          @change="saveConfig('全局配置已保存')"
          min="1"
          max="20"
          class="num-inp"
        />
      </div>
    </div>

    <!-- ===== 二、场景策略矩阵 ===== -->
    <div class="section-title">
      场景策略矩阵 <span class="count">({{ policies.length }}条)</span>
    </div>
    <div class="table-wrap">
      <table class="policy-table">
        <thead>
          <tr>
            <th style="width: 80px">场景</th>
            <th style="width: 200px">检测范围 (DetectTypes)</th>
            <th style="width: 160px">决策关注 (Types)</th>
            <th style="width: 55px">阈值</th>
            <th style="width: 50px">风险</th>
            <th style="width: 80px">动作</th>
            <th style="width: 45px">启用</th>
            <th style="width: 65px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(p, idx) in policies"
            :key="p.id"
            :class="{ disabled: !p.enabled }"
          >
            <template v-if="editIdx === idx">
              <td>
                <input
                  class="cell-inp"
                  v-model="p.sceneName"
                  style="width: 80px"
                />
              </td>
              <td>
                <div class="type-chips">
                  <span
                    v-for="t in ALL_TYPES"
                    :key="'d' + t"
                    :class="[
                      'chip',
                      { selected: (p.detectTypes || []).includes(t) },
                    ]"
                    @click="toggleArr(p.detectTypes, t)"
                    >{{ TYPE_LABELS[t] }}</span
                  >
                </div>
              </td>
              <td>
                <div class="type-chips">
                  <span
                    v-for="t in ALL_TYPES"
                    :key="'t' + t"
                    :class="['chip', { selected: (p.types || []).includes(t) }]"
                    @click="toggleArr(p.types, t)"
                    >{{ TYPE_LABELS[t] }}</span
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
                  class="btn-save"
                  @click="finishEdit(idx)"
                >
                  保存
                </button>
              </td>
            </template>
            <template v-else>
              <td class="scene-name">{{ p.sceneName }}</td>
              <td>
                <span
                  v-for="t in p.detectTypes || []"
                  :key="'d' + t"
                  class="chip readonly selected"
                  >{{ TYPE_LABELS[t] || t }}</span
                >
              </td>
              <td>
                <span
                  v-for="t in p.types || []"
                  :key="'t' + t"
                  class="chip readonly"
                  style="
                    background: #fef3c7;
                    border-color: #fde047;
                    color: #92400e;
                  "
                  >{{ TYPE_LABELS[t] || t }}</span
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
                      CRITICAL: '#7c3aed',
                    }[p.riskLevel],
                    fontWeight: 700,
                  }"
                  >{{ RISK_LABELS[p.riskLevel] || p.riskLevel }}</span
                >
              </td>
              <td>{{ ACTION_LABELS[p.action] || p.action }}</td>
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
                  class="btn-edit"
                  @click="startEdit(idx)"
                >
                  编辑
                </button>
                <button
                  class="btn-del"
                  @click="removePolicy(p.id)"
                >
                  删
                </button>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 新增场景 -->
    <div
      class="card"
      style="margin-top: 12px"
    >
      <div class="add-row">
        <span class="add-label">场景名</span
        ><input
          class="cell-inp"
          v-model="newScene.sceneName"
          placeholder="如：法律场景"
          style="width: 100px"
        />
        <span class="add-label">阈值</span
        ><select
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
        <span class="add-label">风险</span
        ><select
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
        <span class="add-label">动作</span
        ><select
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
          + 添加场景
        </button>
      </div>
      <div
        class="add-row"
        style="margin-top: 6px"
      >
        <span class="add-label">检测</span>
        <div class="type-chips">
          <span
            v-for="t in ALL_TYPES"
            :key="'nd' + t"
            :class="['chip', { selected: newScene.detectTypes.includes(t) }]"
            @click="toggleArr(newScene.detectTypes, t)"
            >{{ TYPE_LABELS[t] }}</span
          >
        </div>
      </div>
      <div
        class="add-row"
        style="margin-top: 4px"
      >
        <span class="add-label">决策</span>
        <div class="type-chips">
          <span
            v-for="t in ALL_TYPES"
            :key="'nt' + t"
            :class="['chip', { selected: newScene.types.includes(t) }]"
            @click="toggleArr(newScene.types, t)"
            >{{ TYPE_LABELS[t] }}</span
          >
        </div>
      </div>
    </div>

    <!-- ===== 三、词典管理 (可展开) ===== -->
    <div
      class="section-title collapse-title"
      @click="dictSection = !dictSection"
      style="cursor: pointer"
    >
      {{ dictSection ? "▼" : "▶" }} 词典管理 (高级)
      <span class="subtitle">白名单/黑名单词条，影响NLP实体识别精度</span>
    </div>
    <div
      v-if="dictSection"
      class="card"
    >
      <div
        v-if="dictMsg"
        class="msg"
        :class="{ error: dictMsg.includes('失败') }"
      >
        {{ dictMsg }}
      </div>
      <div
        class="row"
        style="gap: 10px; flex-wrap: wrap; margin-bottom: 10px"
      >
        <select
          v-model="dictType"
          @change="loadDict()"
          class="sel"
        >
          <option
            v-for="d in DICT_TYPES"
            :key="d.value"
            :value="d.value"
          >
            {{ d.label }}
          </option>
        </select>
        <input
          v-model="newTerm"
          placeholder="输入词条"
          class="cell-inp"
          style="width: 140px"
        />
        <button
          class="btn-add"
          @click="addDictEntry"
        >
          添加词条
        </button>
        <button
          class="btn-reload"
          @click="reloadDicts"
        >
          热重载
        </button>
      </div>
      <div
        v-if="dictEntries.length"
        class="dict-list"
      >
        <span
          v-for="e in dictEntries"
          :key="e.id"
          class="dict-tag"
        >
          {{ e.term }}
          <button
            class="dict-del"
            @click="delDictEntry(e.id, e.term)"
          >
            x
          </button>
        </span>
      </div>
      <div
        v-else
        class="empty-hint"
      >
        暂无词条，选择一个词典类型后添加
      </div>
    </div>

    <!-- ===== 四、自定义正则 (可展开) ===== -->
    <div
      class="section-title collapse-title"
      @click="
        ruleSection = !ruleSection;
        if (ruleSection) fetchRules();
      "
      style="cursor: pointer"
    >
      {{ ruleSection ? "▼" : "▶" }} 自定义正则规则 (高级)
      <span class="subtitle"
        >匹配企业内部特殊格式（如项目编号、内部工单号）</span
      >
    </div>
    <div
      v-if="ruleSection"
      class="card"
    >
      <div
        v-if="ruleMsg"
        class="msg"
        :class="{ error: ruleMsg.includes('失败') }"
      >
        {{ ruleMsg }}
      </div>
      <div
        class="row"
        style="gap: 8px; flex-wrap: wrap; margin-bottom: 10px"
      >
        <input
          v-model="newRule.patternName"
          placeholder="规则名称"
          class="cell-inp"
          style="width: 120px"
        />
        <input
          v-model="newRule.regex"
          placeholder="正则表达式"
          class="cell-inp"
          style="width: 180px"
        />
        <input
          v-model="newRule.description"
          placeholder="描述(可选)"
          class="cell-inp"
          style="width: 140px"
        />
        <button
          class="btn-add"
          @click="addRule"
        >
          添加
        </button>
      </div>
      <div
        v-if="rules.length"
        class="rules-list"
      >
        <div
          v-for="r in rules"
          :key="r.patternName"
          class="rule-item"
        >
          <code class="rule-name">{{ r.patternName }}</code>
          <code class="rule-regex">{{ r.regex }}</code>
          <span
            v-if="r.description"
            class="rule-desc"
            >{{ r.description }}</span
          >
          <label class="toggle"
            ><input
              type="checkbox"
              :checked="r.isEnabled"
              @change="toggleRule(r)" /><span class="toggle-slider"></span
          ></label>
          <button
            class="btn-del"
            @click="deleteRule(r.patternName)"
          >
            删
          </button>
        </div>
      </div>
      <div
        v-else
        class="empty-hint"
      >
        暂无自定义正则规则
      </div>
    </div>

    <div class="note">
      检测范围(蓝色)决定该场景下引擎扫描哪些敏感类型；决策关注(黄色)决定命中哪些类型触发策略。修改后即时生效。
    </div>
  </div>
</template>

<style scoped>
  .policy-page {
    width: 100%;
  }
  .page-head {
    margin-bottom: 14px;
  }
  .page-head h2 {
    margin: 0;
    font-size: 1.3rem;
  }
  .loading {
    text-align: center;
    color: #94a3b8;
    padding: 30px 0;
  }
  .msg {
    padding: 6px 12px;
    border-radius: 6px;
    background: #ecfdf5;
    color: #059669;
    font-size: 0.82rem;
    margin-bottom: 8px;
  }
  .msg.error {
    background: #fef2f2;
    color: #dc2626;
  }
  .section-title {
    font-size: 0.9rem;
    font-weight: 600;
    color: #475569;
    margin: 16px 0 8px;
  }
  .section-title.collapse-title:hover {
    color: #6366f1;
  }
  .subtitle {
    font-weight: 400;
    font-size: 0.76rem;
    color: #94a3b8;
    margin-left: 8px;
  }
  .count {
    font-weight: 400;
    color: #94a3b8;
    font-size: 0.8rem;
  }

  .card {
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    padding: 14px 16px;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 0.84rem;
    color: #334155;
  }

  .table-wrap {
    overflow-x: auto;
  }
  .policy-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.8rem;
  }
  .policy-table th {
    text-align: left;
    padding: 8px 8px;
    background: #f8fafc;
    color: #64748b;
    font-weight: 600;
    border-bottom: 2px solid #e2e8f0;
    white-space: nowrap;
  }
  .policy-table td {
    padding: 6px 8px;
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

  .type-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 2px;
  }
  .chip {
    padding: 1px 6px;
    font-size: 0.68rem;
    border: 1px solid #e2e8f0;
    border-radius: 3px;
    cursor: pointer;
    background: #fff;
    color: #94a3b8;
    user-select: none;
    white-space: nowrap;
  }
  .chip.selected {
    background: #dbeafe;
    border-color: #93c5fd;
    color: #1e40af;
  }
  .chip.readonly {
    cursor: default;
    font-size: 0.65rem;
  }

  .cell-inp {
    padding: 4px 8px;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    font-size: 0.82rem;
  }
  .sel,
  .sel-sm {
    padding: 4px 8px;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    font-size: 0.82rem;
  }
  .num-inp {
    width: 55px;
    text-align: center;
    padding: 4px 6px;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    font-size: 0.82rem;
  }

  .toggle {
    position: relative;
    display: inline-block;
    width: 32px;
    height: 18px;
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
    border-radius: 18px;
    transition: 0.2s;
  }
  .toggle-slider::before {
    content: "";
    position: absolute;
    height: 12px;
    width: 12px;
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
    transform: translateX(14px);
  }

  .btn-save {
    padding: 3px 10px;
    border-radius: 5px;
    font-size: 0.76rem;
    background: #ecfdf5;
    color: #059669;
    border: 1px solid #a7f3d0;
    cursor: pointer;
  }
  .btn-edit {
    padding: 2px 8px;
    border-radius: 4px;
    font-size: 0.74rem;
    color: #6366f1;
    border: 1px solid #c7d2fe;
    background: #fff;
    cursor: pointer;
    margin-right: 3px;
  }
  .btn-del {
    padding: 2px 8px;
    border-radius: 4px;
    font-size: 0.74rem;
    color: #dc2626;
    border: 1px solid #fecaca;
    background: #fff;
    cursor: pointer;
  }
  .btn-add {
    padding: 5px 14px;
    background: #6366f1;
    color: #fff;
    border: none;
    border-radius: 7px;
    cursor: pointer;
    font-size: 0.82rem;
  }
  .btn-reload {
    padding: 5px 12px;
    background: #f1f5f9;
    color: #475569;
    border: 1px solid #e2e8f0;
    border-radius: 7px;
    cursor: pointer;
    font-size: 0.8rem;
  }

  .add-row {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    font-size: 0.82rem;
  }
  .add-label {
    color: #64748b;
    min-width: 40px;
    font-size: 0.78rem;
  }

  .dict-list {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .dict-tag {
    padding: 3px 10px;
    background: #f1f5f9;
    border-radius: 14px;
    font-size: 0.78rem;
    color: #334155;
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .dict-del {
    border: none;
    background: none;
    color: #94a3b8;
    cursor: pointer;
    font-size: 0.8rem;
    padding: 0;
    line-height: 1;
  }
  .dict-del:hover {
    color: #dc2626;
  }

  .rules-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .rule-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 10px;
    background: #f8fafc;
    border-radius: 6px;
    font-size: 0.78rem;
  }
  .rule-name {
    font-weight: 600;
    color: #6366f1;
    font-size: 0.76rem;
  }
  .rule-regex {
    color: #64748b;
    font-size: 0.74rem;
  }
  .rule-desc {
    color: #94a3b8;
    font-size: 0.72rem;
  }
  .empty-hint {
    color: #94a3b8;
    font-size: 0.8rem;
    text-align: center;
    padding: 12px 0;
  }

  .note {
    margin-top: 14px;
    font-size: 0.74rem;
    color: #94a3b8;
    line-height: 1.5;
  }
</style>
