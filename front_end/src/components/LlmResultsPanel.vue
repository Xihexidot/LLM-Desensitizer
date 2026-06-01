<script setup>
  import { ref, watch } from "vue";
  import "highlight.js/styles/github-dark.css";

  const props = defineProps({
    results: {
      type: Object,
      required: true,
    },
    displayedLlmText: {
      type: String,
      default: "",
    },
    isTyping: {
      type: Boolean,
      default: false,
    },
    highlightedOriginalHtml: {
      type: String,
      default: "",
    },
    desensitizedPrompt: {
      type: String,
      default: "",
    },
    llmText: {
      type: String,
      default: "",
    },
    providerIcon: {
      type: String,
      default: "⚪",
    },
    loading: {
      type: Boolean,
      default: false,
    },
  });

  defineEmits(["copy", "export-markdown", "export-pdf", "share-screenshot"]);

  const resultsRoot = ref(null);
  const renderedMarkdown = ref("");
  let markdownRenderer = null;

  async function ensureMarkdownRenderer() {
    if (markdownRenderer) {
      return markdownRenderer;
    }

    const [{ default: MarkdownIt }, { default: hljs }] = await Promise.all([
      import("markdown-it"),
      import("highlight.js"),
    ]);

    markdownRenderer = new MarkdownIt({
      html: false,
      linkify: true,
      typographer: true,
      highlight(str, lang) {
        if (lang && hljs.getLanguage(lang)) {
          try {
            return (
              '<pre class="hljs"><code>' +
              hljs.highlight(str, { language: lang, ignoreIllegals: true })
                .value +
              "</code></pre>"
            );
          } catch {}
        }

        return (
          '<pre class="hljs"><code>' +
          markdownRenderer.utils.escapeHtml(str) +
          "</code></pre>"
        );
      },
    });

    return markdownRenderer;
  }

  async function updateRenderedMarkdown(text) {
    if (!props.results?.llmResponse?.success || !text) {
      renderedMarkdown.value = "";
      return;
    }

    const renderer = await ensureMarkdownRenderer();
    renderedMarkdown.value = renderer.render(text);
  }

  watch(
    () => props.displayedLlmText,
    async (value) => {
      await updateRenderedMarkdown(value);
    },
    { immediate: true },
  );

  function getRootElement() {
    return resultsRoot.value;
  }

  defineExpose({ getRootElement });
</script>

<template>
  <div
    ref="resultsRoot"
    class="results-section"
  >
    <div class="results-header">
      <h3>📊 处理结果</h3>
      <div class="export-actions">
        <button
          class="btn btn-small btn-secondary"
          title="导出 Markdown"
          :disabled="loading"
          @click="$emit('export-markdown')"
        >
          ⬇️ MD
        </button>
        <button
          class="btn btn-small btn-secondary"
          title="导出 PDF"
          :disabled="loading"
          @click="$emit('export-pdf')"
        >
          ⬇️ PDF
        </button>
        <button
          class="btn btn-small btn-secondary"
          title="生成长截图"
          :disabled="loading"
          @click="$emit('share-screenshot')"
        >
          📸 截图
        </button>
      </div>
    </div>

    <div class="comparison-section">
      <div class="comparison-panel original">
        <div class="panel-header">
          <h4>原始提示词</h4>
          <div class="panel-actions">
            <button
              class="copy-btn"
              title="复制到剪贴板"
              @click="$emit('copy', results.originalPrompt, '原始提示词')"
            >
              📋 复制
            </button>
          </div>
        </div>
        <div
          class="content-display"
          v-html="highlightedOriginalHtml"
        ></div>
      </div>

      <div class="comparison-panel desensitized">
        <div class="panel-header">
          <h4>脱敏后提示词</h4>
          <div class="panel-actions">
            <button
              class="copy-btn"
              title="复制到剪贴板"
              @click="$emit('copy', results.desensitizedPrompt, '脱敏后提示词')"
            >
              📋 复制
            </button>
          </div>
        </div>
        <pre class="content-display">{{ desensitizedPrompt }}</pre>
      </div>
    </div>

    <div class="llm-response-panel">
      <div class="panel-header">
        <h4>{{ providerIcon }} {{ results.llmProvider }} 响应</h4>
        <div class="panel-actions">
          <button
            class="copy-btn"
            title="复制到剪贴板"
            @click="$emit('copy', llmText, 'LLM响应')"
          >
            📋 复制
          </button>
        </div>
      </div>

      <div class="llm-response-content">
        <template v-if="results.llmResponse && results.llmResponse.success">
          <div
            v-if="renderedMarkdown"
            class="markdown-body"
            v-html="renderedMarkdown"
          ></div>
          <pre
            v-else
            class="content-display"
            >{{ displayedLlmText }}</pre
          >
          <span
            v-if="isTyping"
            class="cursor-blink"
            >|</span
          >
        </template>
        <template v-else>
          <div class="error-message">❌ {{ llmText }}</div>
        </template>
      </div>

      <div class="performance-info">
        <small>
          脱敏耗时: {{ results.processingTime.desensitization }}ms |
          LLM响应耗时: {{ results.processingTime.llm }}ms | 总计耗时:
          {{
            results.processingTime.desensitization + results.processingTime.llm
          }}ms
        </small>
        <small>
          附件原长度: {{ results.attachmentInfo?.originalLength || 0 }} |
          发送长度: {{ results.attachmentInfo?.sentLength || 0 }} | 是否截断:
          {{ results.attachmentInfo?.truncated ? "是" : "否" }}
        </small>
        <small>
          提示词总长度: {{ (results.originalPrompt || "").length }}
        </small>
      </div>
    </div>
  </div>
</template>

<style scoped>
  .results-section {
    margin-bottom: 30px;
  }

  .results-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    margin-bottom: 16px;
  }

  .results-header h3 {
    margin: 0;
    color: #646cff;
  }

  .export-actions,
  .panel-actions {
    display: flex;
    gap: 10px;
  }

  .comparison-section {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 20px;
    margin-bottom: 20px;
  }

  .comparison-panel,
  .llm-response-panel {
    background: var(--card-bg);
    border: 1px solid var(--border);
    border-radius: 12px;
    overflow: hidden;
  }

  .llm-response-panel {
    margin-top: 20px;
  }

  .panel-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    padding: 16px 18px;
    border-bottom: 1px solid var(--border);
  }

  .panel-header h4 {
    margin: 0;
    color: var(--text);
  }

  .content-display,
  .llm-response-content {
    padding: 18px;
    margin: 0;
    white-space: pre-wrap;
    word-break: break-word;
    color: var(--text);
    background: var(--card-bg);
  }

  .copy-btn,
  .btn {
    border-radius: 8px;
    border: 1px solid var(--border);
    cursor: pointer;
    transition: all 0.2s ease;
  }

  .copy-btn {
    padding: 8px 12px;
    background: var(--card-bg);
    color: var(--text-light);
  }

  .copy-btn:hover,
  .btn:hover:not(:disabled) {
    border-color: #646cff;
    color: var(--text);
  }

  .btn {
    padding: 8px 14px;
    font-size: 0.9em;
  }

  .btn-secondary {
    background: #4b5563;
    color: #e5e7eb;
  }

  .btn-small {
    min-width: 88px;
  }

  .btn:disabled,
  .copy-btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .performance-info {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 14px 18px 18px;
    border-top: 1px solid var(--border);
    color: var(--text-light);
  }

  .markdown-body :deep(pre) {
    overflow-x: auto;
  }

  .markdown-body :deep(p:first-child) {
    margin-top: 0;
  }

  .cursor-blink {
    display: inline-block;
    margin-left: 4px;
    color: #646cff;
    animation: blink 1s step-end infinite;
  }

  .error-message {
    color: #ef4444;
    background: rgba(239, 68, 68, 0.08);
    border-radius: 8px;
    padding: 12px;
  }

  .content-display :deep(.entity-highlight) {
    padding: 2px 4px;
    border-radius: 4px;
    background: rgba(100, 108, 255, 0.15);
    color: #4f46e5;
  }

  @keyframes blink {
    50% {
      opacity: 0;
    }
  }

  @media (max-width: 960px) {
    .comparison-section {
      grid-template-columns: 1fr;
    }

    .results-header,
    .panel-header {
      flex-direction: column;
      align-items: flex-start;
    }

    .export-actions,
    .panel-actions {
      flex-wrap: wrap;
    }
  }
</style>
