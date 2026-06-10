<script setup>
  import { ref, onMounted } from "vue";
  import { API_BASE_URL } from "../config";

  const stats = ref({ byTargetProvider: [], byChannel: [] });
  const loading = ref(false);
  const error = ref("");

  const PROVIDER_LABELS = {
    DeepSeek: "DeepSeek",
    ChatGPT: "ChatGPT",
    Kimi: "Kimi",
    通义千问: "通义千问",
    豆包: "豆包",
    Claude: "Claude",
    Gemini: "Gemini",
    文心一言: "文心一言",
    混元: "混元",
    Perplexity: "Perplexity",
    DEEPSEEK: "DeepSeek (API)",
    OPENAI: "OpenAI (API)",
    QWEN: "通义千问 (API)",
    DOUBAO: "豆包 (API)",
    KIMI: "Kimi (API)",
    HUNYUAN: "混元 (API)",
    CLAUDE: "Claude (API)",
    OLLAMA: "Ollama (本地)",
  };

  async function loadStats() {
    loading.value = true;
    error.value = "";
    try {
      const res = await fetch(`${API_BASE_URL}/gateway/v1/audit/stats`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      stats.value = data;
    } catch (e) {
      error.value = "加载失败: " + e.message;
    } finally {
      loading.value = false;
    }
  }

  onMounted(loadStats);

  function label(name) {
    return PROVIDER_LABELS[name] || name || "未知";
  }
</script>

<template>
  <div class="provider-page">
    <div class="page-head">
      <h2>外部 LLM 调用监控</h2>
      <button
        class="btn-refresh"
        @click="loadStats"
        :disabled="loading"
      >
        {{ loading ? "刷新中..." : "刷新" }}
      </button>
    </div>
    <div
      v-if="error"
      class="gw-error"
    >
      {{ error }}
    </div>

    <div class="info-banner">
      统计今日通过插件和网关 API
      发往各外部大模型平台的请求次数。用于监控员工访问的 LLM
      服务分布、发现异常调用行为。
    </div>

    <div
      v-if="stats.byTargetProvider?.length"
      class="provider-grid"
    >
      <div
        v-for="p in stats.byTargetProvider"
        :key="p.target_provider"
        class="provider-card"
      >
        <div class="p-name">{{ label(p.target_provider) }}</div>
        <div class="p-count">{{ p.cnt }} <span class="unit">次</span></div>
        <div class="p-tag">
          {{
            (p.target_provider || "").includes("(API)")
              ? "API 调用"
              : "网页插件"
          }}
        </div>
      </div>
    </div>

    <div
      v-else-if="!loading"
      class="empty"
    >
      今日暂无外部 LLM 调用记录。<br />员工通过浏览器插件在 DeepSeek
      等网站发送消息后，统计数据将在此展示。
    </div>

    <div class="note">
      说明：此页面监控的是员工通过企业插件或网关发往外部 LLM
      的调用统计，用于安全审计与异常检测。后端本身不转发员工请求到外部模型。
    </div>
  </div>
</template>

<style scoped>
  .provider-page {
    width: 100%;
  }
  .page-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 14px;
  }
  .page-head h2 {
    margin: 0;
    font-size: 1.3rem;
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
  .gw-error {
    color: #ef4444;
    padding: 12px;
    background: #fef2f2;
    border-radius: 8px;
    margin-bottom: 12px;
  }

  .info-banner {
    background: #f0f9ff;
    border: 1px solid #bae6fd;
    border-radius: 8px;
    padding: 12px 16px;
    font-size: 0.84rem;
    color: #0369a1;
    margin-bottom: 16px;
    line-height: 1.5;
  }

  .provider-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 12px;
  }
  @media (max-width: 900px) {
    .provider-grid {
      grid-template-columns: repeat(2, 1fr);
    }
  }
  .provider-card {
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    padding: 18px 16px;
    background: #fff;
    text-align: center;
  }
  .p-name {
    font-size: 0.95rem;
    font-weight: 700;
    color: #1e293b;
    margin-bottom: 10px;
  }
  .p-count {
    font-size: 2.2rem;
    font-weight: 800;
    color: #6366f1;
  }
  .unit {
    font-size: 0.85rem;
    font-weight: 400;
    color: #94a3b8;
  }
  .p-tag {
    font-size: 0.72rem;
    color: #94a3b8;
    margin-top: 4px;
  }
  .empty {
    text-align: center;
    color: #94a3b8;
    padding: 40px 0;
    font-size: 0.9rem;
    line-height: 1.6;
  }
  .note {
    margin-top: 20px;
    font-size: 0.75rem;
    color: #94a3b8;
    line-height: 1.5;
  }
</style>
