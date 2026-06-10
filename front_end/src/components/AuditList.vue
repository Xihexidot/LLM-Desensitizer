<script setup>
  import { ref, onMounted, onUnmounted } from "vue";
  import { API_BASE_URL } from "../config";

  const emit = defineEmits(["view-detail"]);

  const events = ref([]);
  const loading = ref(false);
  const error = ref("");
  const filterUserId = ref("");
  const expandedRow = ref(null);

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

  async function loadAuditEvents() {
    loading.value = true;
    error.value = "";
    try {
      let url = `${API_BASE_URL}/gateway/v1/audit/events`;
      if (filterUserId.value)
        url += `?userId=${encodeURIComponent(filterUserId.value)}`;
      const res = await fetch(url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const body = await res.json();
      events.value = Array.isArray(body) ? body : [];
    } catch (e) {
      error.value = "加载失败: " + e.message;
    } finally {
      loading.value = false;
    }
  }

  onMounted(() => {
    loadAuditEvents();
    refreshTimer = setInterval(loadAuditEvents, 10000);
  });
  onUnmounted(() => {
    if (refreshTimer) clearInterval(refreshTimer);
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
</script>

<template>
  <div class="audit-page">
    <div class="page-head">
      <h2>审计日志</h2>
      <div class="toolbar">
        <input
          class="filter-inp"
          v-model="filterUserId"
          placeholder="按用户筛选..."
          @keydown.enter="loadAuditEvents"
        />
        <button
          @click="loadAuditEvents"
          :disabled="loading"
          class="btn-refresh"
        >
          {{ loading ? "刷新中..." : "刷新" }}
        </button>
      </div>
    </div>

    <div
      v-if="error"
      class="gw-error"
    >
      {{ error }}
    </div>
    <div
      v-if="events.length === 0 && !loading"
      class="empty"
    >
      暂无审计记录
    </div>

    <div
      v-if="events.length > 0"
      class="table-wrap"
    >
      <table class="audit-table">
        <thead>
          <tr>
            <th style="width: 150px">时间</th>
            <th>用户</th>
            <th>部门</th>
            <th>渠道</th>
            <th>敏感类型</th>
            <th>风险</th>
            <th>用户操作</th>
            <th style="width: 50px"></th>
            <th style="width: 60px">详情</th>
          </tr>
        </thead>
        <tbody>
          <template
            v-for="e in events"
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
              <td>{{ e.department || "-" }}</td>
              <td>
                <span class="channel-tag">{{
                  CHANNEL_LABELS[e.channel] || e.channel || "-"
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
              <td>
                <a
                  href="#"
                  class="detail-link"
                  @click.prevent.stop="$emit('view-detail', e.eventId)"
                  >详情</a
                >
              </td>
            </tr>
            <tr
              v-if="expandedRow === e.eventId"
              class="expand-row"
            >
              <td colspan="9">
                <div class="expand-content">
                  <div class="content-pair">
                    <div class="content-box">
                      <div class="content-label">原始输入内容</div>
                      <div class="content-text">
                        {{ e.originalContent || "(未记录)" }}
                      </div>
                    </div>
                    <div class="content-box">
                      <div class="content-label">脱敏后内容</div>
                      <div class="content-text">
                        {{ e.processedContent || "(未记录)" }}
                      </div>
                    </div>
                  </div>
                  <div class="content-meta">
                    <span>事件ID: {{ e.eventId }}</span>
                    <span>决策动作: {{ e.decisionAction || "-" }}</span>
                    <span>输出风险: {{ e.outputRiskLevel || "-" }}</span>
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
  .audit-page {
    width: 100%;
  }
  .page-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
  }
  .page-head h2 {
    margin: 0;
    font-size: 1.3rem;
  }
  .toolbar {
    display: flex;
    gap: 8px;
  }
  .filter-inp {
    padding: 6px 12px;
    border: 1px solid #e2e8f0;
    border-radius: 8px;
    font-size: 0.85rem;
    width: 180px;
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

  .empty {
    text-align: center;
    color: #94a3b8;
    padding: 30px 0;
  }
  .gw-error {
    color: #ef4444;
    padding: 12px;
    background: #fef2f2;
    border-radius: 8px;
    margin-bottom: 12px;
  }

  .table-wrap {
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
    white-space: nowrap;
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
  .expand-arrow {
    color: #94a3b8;
    font-size: 0.7rem;
  }

  .detail-link {
    color: #6366f1;
    font-size: 0.82rem;
    text-decoration: none;
  }
  .detail-link:hover {
    text-decoration: underline;
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
    max-height: 150px;
    overflow-y: auto;
    white-space: pre-wrap;
  }
  .content-meta {
    display: flex;
    gap: 20px;
    font-size: 0.78rem;
    color: #64748b;
  }
</style>
