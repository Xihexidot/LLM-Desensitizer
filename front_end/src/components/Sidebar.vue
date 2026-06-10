<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="logo">🔐</div>
      <div class="title">ApiSensitivities</div>
    </div>

    <div
      id="section-health"
      class="panel health-panel"
    >
      <button
        class="quick-btn"
        :disabled="healthLoading"
        @click="$emit('check-health')"
      >
        {{ healthLoading ? "检查中..." : "检查服务" }}
      </button>
      <div
        v-if="healthStatus"
        class="health-status"
        :data-success="healthStatus.includes('✅')"
      >
        服务正常运行
      </div>
    </div>

    <nav class="nav">
      <a
        class="nav-item"
        href="#"
        @click.prevent="$emit('show-home')"
        >脱敏测试</a
      >

      <a
        class="nav-item"
        href="#"
        @click.prevent="$emit('show-dashboard')"
        >仪表盘</a
      >
      <a
        class="nav-item"
        href="#"
        @click.prevent="$emit('show-audit')"
        >审计日志</a
      >
      <a
        class="nav-item"
        href="#"
        @click.prevent="$emit('show-policy')"
        >风险策略</a
      >
      <a
        class="nav-item"
        href="#"
        @click.prevent="$emit('show-providers')"
        >调用监控</a
      >

      <a
        class="nav-item"
        href="#section-history"
        @click.prevent="toggleHistory"
        >历史对话</a
      >
      <div
        v-if="showHistory"
        class="history-inline"
      >
        <div class="history-header">
          <span>最近记录</span>
          <button
            class="history-close"
            @click="toggleHistory"
          >
            收起
          </button>
        </div>
        <ul class="history-list">
          <li
            v-for="h in shortList"
            :key="h.id"
            class="history-item"
            @click="onPick(h.id)"
          >
            <div class="history-time">
              {{ new Date(h.timestamp).toLocaleString() }}
            </div>
            <div class="history-preview">
              {{
                (h.desensitizedPrompt || h.originalPrompt || "").slice(0, 48)
              }}
            </div>
          </li>
        </ul>
      </div>

      <a
        class="nav-item"
        href="#section-about"
        >关于</a
      >
    </nav>

    <div class="quick">
      <div class="quick-title">快捷操作</div>
      <button
        class="quick-btn"
        @click="$emit('scroll-llm')"
      >
        打开助手
      </button>
      <button
        class="quick-btn"
        @click="$emit('toggle-theme')"
      >
        切换主题
      </button>
    </div>
  </aside>
</template>

<script setup>
  import { ref, computed } from "vue";
  const emit = defineEmits([
    "select-history",
    "toggle-theme",
    "check-health",
    "scroll-llm",
    "show-home",
    "show-dashboard",
    "show-audit",
    "show-policy",
    "show-providers",
  ]);
  const props = defineProps({
    healthStatus: String,
    healthLoading: Boolean,
    histories: { type: Array, default: () => [] },
  });
  const showHistory = ref(false);
  const shortList = computed(() => {
    const arr = Array.isArray(props.histories) ? props.histories : [];
    const last = arr.slice(-10);
    return last.reverse();
  });
  function toggleHistory() {
    showHistory.value = !showHistory.value;
  }
  function onPick(id) {
    emit("select-history", id);
  }
</script>

<style scoped>
  .sidebar {
    position: sticky;
    top: 0;
    height: 100vh;
    width: 280px;
    background: var(--sidebar-bg);
    color: var(--text);
    display: flex;
    flex-direction: column;
    padding: 24px 20px;
    border-right: 1px solid var(--border);
    box-shadow: 4px 0 24px rgba(0, 0, 0, 0.02);
    z-index: 10;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 0 4px 24px 4px;
    margin-bottom: 20px;
    border-bottom: 1px solid var(--border);
  }
  .logo {
    width: 40px;
    height: 40px;
    border-radius: 12px;
    background: linear-gradient(135deg, #6366f1, #8b5cf6);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 20px;
    box-shadow: 0 4px 12px rgba(99, 102, 241, 0.3);
  }
  .title {
    font-weight: 800;
    font-size: 18px;
    letter-spacing: -0.5px;
    background: linear-gradient(to right, var(--text), var(--text-light));
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    background-clip: text;
  }
  .nav-item {
    padding: 12px 16px;
    border-radius: 12px;
    color: var(--text-light);
    text-decoration: none;
    margin-bottom: 8px;
    transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
    background-color: transparent;
    border: 1px solid transparent;
    font-weight: 500;
    display: flex;
    align-items: center;
  }
  .nav-item:hover {
    background: var(--header-bg);
    color: var(--primary);
    transform: translateX(4px);
  }
  .nav-section {
    margin: 8px 0;
    padding: 0;
    background: transparent;
    border-radius: 12px;
    border: none;
  }
  .nav-section-title.clickable {
    cursor: pointer;
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 16px;
    border-radius: 12px;
    color: var(--text-light);
    font-weight: 500;
    transition: all 0.2s ease;
  }
  .nav-section-title.clickable:hover {
    background: var(--header-bg);
    color: var(--primary);
  }
  .nav {
    flex: 1;
    overflow-y: auto;
    margin-top: 8px;
  }
  .panel {
    background: var(--card-bg);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 16px;
    margin-bottom: 24px;
    transition: all 0.2s ease;
  }
  .panel:hover {
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
    border-color: var(--primary);
  }
  .health-status {
    margin-top: 12px;
    font-size: 13px;
    font-weight: 500;
    color: var(--success);
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
  }
  .health-status::before {
    content: "";
    width: 6px;
    height: 6px;
    background: currentColor;
    border-radius: 50%;
    box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.2);
  }
  .setting-item {
    padding: 8px 12px;
    margin-bottom: 4px;
    background: transparent;
    border-radius: 8px;
    border: none;
  }
  .setting-item:hover {
    background: rgba(0, 0, 0, 0.02);
  }
  :global(.dark) .setting-item:hover {
    background: rgba(255, 255, 255, 0.02);
  }
  .switch-label {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 14px;
    font-weight: 500;
    color: var(--text);
    cursor: pointer;
    user-select: none;
    padding: 2px 0;
  }
  .switch {
    width: 44px;
    height: 24px;
    background: #e2e8f0;
    border-radius: 99px;
    position: relative;
    transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    border: 1px solid rgba(0, 0, 0, 0.05);
  }
  .switch:hover {
    background: #cbd5e1;
  }
  :global(.dark) .switch {
    background: #334155;
    border-color: rgba(255, 255, 255, 0.1);
  }
  :global(.dark) .switch:hover {
    background: #475569;
  }
  .switch.active {
    background: var(--primary);
    border-color: transparent;
    box-shadow: 0 4px 12px rgba(99, 102, 241, 0.3);
  }
  .switch-handle {
    width: 20px;
    height: 20px;
    background: white;
    border-radius: 50%;
    position: absolute;
    top: 1px;
    left: 1px;
    transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  }
  .switch:active .switch-handle {
    width: 24px;
  }
  .switch.active .switch-handle {
    transform: translateX(20px);
  }
  .switch.active:active .switch-handle {
    transform: translateX(16px);
    width: 24px;
  }
  .quick {
    margin-top: auto;
    padding-top: 20px;
    border-top: 1px solid var(--border);
  }
  .quick-title {
    font-size: 11px;
    font-weight: 700;
    color: var(--text-light);
    margin-bottom: 12px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    padding-left: 4px;
  }
  .quick-btn {
    width: 100%;
    padding: 10px 16px;
    border: 1px solid var(--border);
    background: transparent;
    color: var(--text);
    border-radius: 10px;
    margin-bottom: 8px;
    cursor: pointer;
    transition: all 0.2s ease;
    font-size: 13px;
    font-weight: 500;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .quick-btn:hover {
    background: var(--card-bg);
    border-color: var(--primary);
    color: var(--primary);
    transform: translateY(-1px);
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
  }
  .history-inline {
    padding: 12px;
    border: 1px solid var(--border);
    border-radius: 12px;
    background: var(--card-bg);
    margin: 0 0 16px 0;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.03);
  }
  .history-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 8px;
  }
  .history-close {
    border: 1px solid #e5e7eb;
    background: #f3f4f6;
    color: #374151;
    border-radius: 6px;
    padding: 4px 8px;
    cursor: pointer;
  }
  .history-list {
    list-style: none;
    padding: 0;
    margin: 0;
    max-height: 220px;
    overflow-y: auto;
  }
  .history-item {
    padding: 8px;
    border: 1px solid var(--border);
    border-radius: 6px;
    margin-bottom: 6px;
    background: var(--card-bg);
  }
  .history-time {
    font-size: 12px;
    color: #6b7280;
  }
  .history-preview {
    font-size: 13px;
    color: var(--text);
    margin-top: 4px;
  }
  @media (max-width: 980px) {
    .sidebar {
      position: relative;
      height: auto;
      width: 100%;
      border-right: none;
      border-bottom: 1px solid #1f2937;
    }
  }

  :global(.dark) .history-inline {
    background: var(--card-bg);
    border-color: var(--border);
  }
  :global(.dark) .history-close {
    background: #1f2937;
    color: var(--text-light);
    border-color: var(--border);
  }
  :global(.dark) .history-item {
    background: var(--card-bg);
    border-color: var(--border);
  }
  :global(.dark) .history-time {
    color: var(--text-light);
  }
  :global(.dark) .history-preview {
    color: var(--text);
  }
  .arrow {
    display: inline-block;
    transition: transform 0.2s ease;
    font-size: 10px;
  }
  .arrow.rotated {
    transform: rotate(180deg);
  }
  .nav-section-content {
    margin-top: 10px;
    padding-left: 8px;
    border-left: 1px solid var(--border);
  }
  .nav-sub-section {
    margin-bottom: 12px;
  }
  .nav-sub-section-title {
    font-size: 12px;
    color: #64748b;
    margin-bottom: 6px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }
</style>
