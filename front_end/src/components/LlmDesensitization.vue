<script setup>
  import { ref, reactive, onMounted, watch, onUnmounted } from "vue";
  import { API_BASE_URL } from "../config";
  import LlmProcessingProgress from "./LlmProcessingProgress.vue";
  import LlmDetectionResults from "./LlmDetectionResults.vue";
  import LlmFeatureCards from "./LlmFeatureCards.vue";
  import LlmResultsPanel from "./LlmResultsPanel.vue";
  import {
    buildMarkdownReport,
    exportMarkdownReport,
    exportElementToPdf,
    copyElementScreenshotOrDownload,
  } from "../utils/llmReportExport";

  const emit = defineEmits(["conversation-completed"]);
  const props = defineProps({
    scenarioSettings: {
      type: Object,
      default: () => ({ autoScenario: true, useLlm: false }),
    },
  });

  // LLM提供商选择
  const llmProvider = ref("DEEPSEEK");
  const providers = ref([]);

  // 提供商元数据
  const providerMeta = {
    DEEPSEEK: { label: "DeepSeek", icon: "🟢" },
    DOUBAO: { label: "豆包", icon: "🟡" },
    QWEN: { label: "通义千问", icon: "🟣" },
    KIMI: { label: "Kimi", icon: "🟤" },
    HUNYUAN: { label: "腾讯混元", icon: "🟠" },
    OPENAI: { label: "OpenAI", icon: "🔵" },
  };

  async function fetchProviders() {
    try {
      const res = await fetch(`${API_BASE_URL}/api/llm/configs`);
      if (res.ok) {
        const data = await res.json();
        const newProviders = [];

        for (const [key, config] of Object.entries(data)) {
          if (config.enabled) {
            const meta = providerMeta[key] || { label: key, icon: "⚪" };
            newProviders.push({
              value: key,
              label: meta.label,
              icon: meta.icon,
            });
          }
        }

        providers.value = newProviders;

        // 如果当前选中的提供商不在列表中，选中第一个
        if (
          newProviders.length > 0 &&
          !newProviders.find((p) => p.value === llmProvider.value)
        ) {
          llmProvider.value = newProviders[0].value;
        }
      }
    } catch (e) {
      console.error("获取提供商列表失败:", e);
      // 降级使用默认列表
      providers.value = [
        { value: "DEEPSEEK", label: "DeepSeek", icon: "🟢" },
        { value: "DOUBAO", label: "豆包", icon: "🟡" },
      ];
    }
  }

  // 脱敏策略选择
  const desensitizationStrategy = ref("MASKED");
  const strategies = [
    {
      value: "MASKED",
      label: "标识替换脱敏",
      description: "使用[PHONE]、[EMAIL]等标识替换敏感信息",
      icon: "🔒",
      example: "手机号会变成[PHONE]，邮箱会变成[EMAIL]",
    },
    {
      value: "PARTIAL",
      label: "部分保留脱敏",
      description: "保留部分原始信息，其余部分用*替换",
      icon: "🔍",
      example: "手机号会变成138****8888，身份证会变成1101**********1234",
    },
    {
      value: "GENERALIZATION",
      label: "语义泛化脱敏",
      description: "将具体值替换为类别标签，如“手机号码”“身份证号”",
      icon: "🏷️",
      example: "13812345678 → 手机号码，张三身份证 → 身份证号",
    },
    {
      value: "SEMANTIC",
      label: "语义替换脱敏",
      description: "以词库近义词或语义词替换敏感文本片段，提升自然度",
      icon: "🗣️",
      example: "地址细节替换为同义表达，减少精确定位风险",
    },
  ];

  // 情景感知参数 (从 props 获取，不再本地维护 autoScenarioDetection)
  const manualScenarioType = ref("");
  const scenarioTypes = [
    { value: "", label: "自动识别" },
    { value: "GENERAL_CHAT", label: "普通聊天" },
    { value: "CUSTOMER_SERVICE", label: "客户服务" },
    { value: "MEDICAL_CONSULTATION", label: "医疗咨询" },
    { value: "FINANCIAL_ADVICE", label: "财务建议" },
    { value: "LEGAL_ADVICE", label: "法律咨询" },
    { value: "HR_RECRUITMENT", label: "招聘" },
    { value: "EDUCATION", label: "教育" },
    { value: "TECHNICAL_SUPPORT", label: "技术支持" },
    { value: "GOVERNMENT_SERVICE", label: "政务服务" },
  ];

  // 表单数据
  const formData = reactive({
    prompt: "",
    customParams: "",
    sessionId: Date.now().toString(),
  });
  const noTruncate = ref(true);
  const attachmentLimit = ref(4000);
  const selectedFile = ref(null);
  const selectedFileName = ref("");
  const selectedFileType = ref("");
  const fileLoading = ref(false);
  const displayedLlmText = ref(""); // 用于打字机效果显示的文本
  const isTyping = ref(false);

  // 打字机效果函数
  function startTypewriter(text) {
    displayedLlmText.value = "";
    isTyping.value = true;
    let i = 0;
    const speed = 20; // 打字速度 (ms)

    const type = () => {
      if (i < text.length) {
        displayedLlmText.value += text.charAt(i);
        i++;
        // 遇到标点符号稍微停顿一下，更像真人
        let delay = speed;
        if (["，", "。", "！", "？", "\n"].includes(text.charAt(i - 1))) {
          delay = speed * 2.5;
        }
        setTimeout(type, delay);
      } else {
        isTyping.value = false;
      }
    };
    type();
  }

  // 生成带高亮的原始文本 HTML
  function getHighlightedOriginal() {
    if (!results.value?.originalPrompt || !detectedEntities.value?.length) {
      return formatText(results.value?.originalPrompt || "");
    }

    let text = results.value.originalPrompt;
    // 按位置从后往前替换，以免破坏索引
    const sortedEntities = [...detectedEntities.value].sort(
      (a, b) => b.start - a.start,
    );

    let lastIndex = text.length;
    let parts = [];

    // 从后往前切
    for (const entity of sortedEntities) {
      if (entity.end > lastIndex) continue; // 防止重叠或越界

      // 后半部分（普通文本）
      if (entity.end < lastIndex) {
        parts.unshift(text.substring(entity.end, lastIndex));
      }

      // 敏感部分（高亮）
      const typeClass = `entity-highlight type-${entity.type.toLowerCase()}`;
      const content = text.substring(entity.start, entity.end);
      parts.unshift(
        `<span class="${typeClass}" title="${entity.type}">${content}</span>`,
      );

      lastIndex = entity.start;
    }

    // 最前面的部分
    if (lastIndex > 0) {
      parts.unshift(text.substring(0, lastIndex));
    }

    return parts.join("");
  }

  function inferDataType(name) {
    if (!name || !name.includes(".")) return "BINARY";
    const ext = name.toLowerCase().split(".").pop();
    if (ext === "txt") return "TEXT";
    if (ext === "json") return "JSON";
    if (["jpg", "jpeg", "png", "gif", "bmp"].includes(ext)) return "IMAGE";
    if (["mp3", "wav", "flac"].includes(ext)) return "AUDIO";
    if (ext === "pdf") return "PDF";
    if (ext === "doc" || ext === "docx") return "DOC";
    if (ext === "xls" || ext === "xlsx") return "EXCEL";
    return "BINARY";
  }

  function onFileChange(e) {
    const f = e.target.files && e.target.files[0];
    if (!f) return;
    selectedFile.value = f;
    selectedFileName.value = f.name;
    selectedFileType.value = inferDataType(f.name);
  }

  function removeFile() {
    selectedFile.value = null;
    selectedFileName.value = "";
    selectedFileType.value = "";
  }

  async function extractFileContent() {
    if (!selectedFile.value) return "";
    fileLoading.value = true;
    try {
      const fd = new FormData();
      fd.append("file", selectedFile.value);
      fd.append("dataType", selectedFileType.value);
      fd.append("language", "zh");
      fd.append(
        "autoScenarioDetection",
        props.scenarioSettings.autoScenario ? "true" : "false",
      );
      if (props.scenarioSettings.useLlm)
        fd.append("metadata.useLlmScenario", "true");
      if (manualScenarioType.value)
        fd.append("manualScenarioType", manualScenarioType.value);
      const mapped = mapStrategyToBackend(desensitizationStrategy.value);
      if (mapped) fd.append("strategy", mapped);
      const resp = await fetch(`${API_BASE_URL}/desensitize/binary`, {
        method: "POST",
        body: fd,
      });
      if (!resp.ok) throw new Error(`文件解析失败: ${resp.status}`);
      const data = await resp.json();
      return data?.originalContent || "";
    } finally {
      fileLoading.value = false;
    }
  }

  // 状态管理
  const loading = ref(false);
  const error = ref("");
  const results = ref(null);
  const detectedEntities = ref([]);
  const processingStep = ref("");
  const collapsedDetection = ref(false);
  const resultsPanelRef = ref(null);

  const currentPercent = ref(0);
  let rafId = null;
  let startTime = 0;
  let startPercent = 0;
  let endPercent = 0;
  let durationMs = 800;
  function transitionTo(target, duration) {
    if (rafId) cancelAnimationFrame(rafId);
    startTime = Date.now();
    startPercent = currentPercent.value;
    endPercent = target;
    durationMs = duration;
    const step = () => {
      const elapsed = Date.now() - startTime;
      const p = Math.min(1, elapsed / durationMs);
      currentPercent.value = Math.round(
        startPercent + (endPercent - startPercent) * p,
      );
      if (p < 1) {
        rafId = requestAnimationFrame(step);
      } else {
        rafId = null;
      }
    };
    rafId = requestAnimationFrame(step);
  }
  watch(
    () => processingStep.value,
    () => {
      const val = processingStep.value;
      if (val === stepDescriptions.DETECTING) transitionTo(20, 800);
      else if (val === stepDescriptions.DESENSITIZING) transitionTo(50, 1200);
      else if (val === stepDescriptions.CALLING_LLM) transitionTo(85, 2500);
      else if (val === stepDescriptions.COMPLETED) transitionTo(100, 800);
    },
  );
  watch(
    () => loading.value,
    (v) => {
      if (v) {
        currentPercent.value = 0;
        transitionTo(10, 300);
      }
    },
  );
  onUnmounted(() => {
    if (rafId) cancelAnimationFrame(rafId);
  });

  // 步骤描述
  const stepDescriptions = {
    DETECTING: "正在检测敏感信息...",
    DESENSITIZING: "正在执行脱敏处理...",
    CALLING_LLM: "正在调用大语言模型...",
    COMPLETED: "处理完成",
  };

  function createEmptyAttachmentInfo() {
    return { originalLength: 0, sentLength: 0, truncated: false };
  }

  function getResultsPanelElement() {
    return resultsPanelRef.value?.getRootElement?.() ?? null;
  }

  function buildAttachmentInfo(attachment) {
    const originalLength = attachment ? attachment.length : 0;
    let sentAttachment = attachment;
    if (
      attachment &&
      !noTruncate.value &&
      attachment.length > attachmentLimit.value
    ) {
      sentAttachment =
        attachment.slice(0, attachmentLimit.value) + "\n...[内容已截断]";
    }

    return {
      content: sentAttachment,
      info: {
        originalLength,
        sentLength: sentAttachment.length,
        truncated: originalLength > sentAttachment.length,
      },
    };
  }

  function parseCustomParams() {
    try {
      if (formData.customParams.trim()) {
        return JSON.parse(formData.customParams);
      }
    } catch (parseError) {
      console.warn("自定义参数解析失败，使用默认参数", parseError);
    }
    return {};
  }

  function saveConversationHistory(originalPrompt, desensitizedPrompt) {
    try {
      const text = getLlmText();
      startTypewriter(text);
      emit("conversation-completed", {
        id: formData.sessionId + "-" + Date.now().toString(),
        timestamp: Date.now(),
        provider: llmProvider.value,
        originalPrompt,
        desensitizedPrompt,
        responseText: text,
      });
    } catch {}
  }

  // 处理LLM请求（带脱敏）
  async function processWithDesensitization() {
    if (!formData.prompt.trim()) {
      error.value = "请输入要发送给LLM的内容";
      return;
    }

    // 重置状态
    loading.value = true;
    error.value = "";
    results.value = null;
    detectedEntities.value = [];

    try {
      processingStep.value = stepDescriptions.DETECTING;
      console.log("开始执行脱敏+LLM处理流程");
      let attachmentInfo = createEmptyAttachmentInfo();
      let attachment = "";
      if (selectedFile.value) {
        processingStep.value = "正在解析附件...";
        attachment = await extractFileContent();
        const attachmentResult = buildAttachmentInfo(attachment);
        attachment = attachmentResult.content;
        attachmentInfo = attachmentResult.info;
        results.value = { attachmentInfo };
      }
      const combinedPrompt = attachment
        ? `${formData.prompt}\n\n[附件内容]\n${attachment}`
        : formData.prompt;

      // 第一步：先进行脱敏处理
      processingStep.value = stepDescriptions.DESENSITIZING;
      const desensitizeResponse = await fetch(
        `${API_BASE_URL}/desensitize/text`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            content: combinedPrompt,
            strategy: mapStrategyToBackend(desensitizationStrategy.value),
            language: "zh",
            autoScenarioDetection: props.scenarioSettings.autoScenario,
            manualScenarioType: manualScenarioType.value || undefined,
            metadata: {
              useLlmScenario: props.scenarioSettings.useLlm,
            },
          }),
        },
      );

      if (!desensitizeResponse.ok) {
        throw new Error(`脱敏服务请求失败: ${desensitizeResponse.status}`);
      }

      const desensitizeData = await desensitizeResponse.json();
      detectedEntities.value = desensitizeData.detectedEntities || [];

      // 第二步：调用LLM服务
      processingStep.value = stepDescriptions.CALLING_LLM;
      console.log(`调用${llmProvider.value}服务，使用脱敏后的提示词`);
      const llmParams = parseCustomParams();

      const llmResponse = await fetch(`${API_BASE_URL}/api/llm/proxy`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          provider: llmProvider.value,
          prompt: desensitizeData.desensitizedContent,
          sessionId: formData.sessionId,
          parameters: llmParams,
        }),
      });

      if (!llmResponse.ok) {
        throw new Error(`LLM服务请求失败: ${llmResponse.status}`);
      }

      const llmData = await llmResponse.json();

      // 组合结果
      results.value = {
        originalPrompt: combinedPrompt,
        desensitizedPrompt: desensitizeData.desensitizedContent,
        llmProvider: llmProvider.value,
        llmResponse: llmData,
        processingTime: {
          desensitization: desensitizeData.processingTime || 0,
          llm: llmData.processingTime || 0,
        },
        attachmentInfo,
      };

      processingStep.value = stepDescriptions.COMPLETED;
      console.log("脱敏+LLM处理流程完成");
      saveConversationHistory(
        combinedPrompt,
        desensitizeData.desensitizedContent,
      );
    } catch (err) {
      console.error("处理过程中发生错误:", err);
      error.value = `处理失败: ${err.message}`;
    } finally {
      loading.value = false;
    }
  }

  // 复制内容到剪贴板
  async function copyToClipboard(text, label) {
    try {
      await navigator.clipboard.writeText(text);
      // 可以添加一个toast提示
      console.log(`${label}已复制到剪贴板`);
    } catch (err) {
      console.error("复制失败:", err);
    }
  }

  // 导出 Markdown
  function exportMarkdown() {
    if (!results.value) return;
    const content = buildMarkdownReport(
      results.value,
      getLlmText(),
      detectedEntities.value.length,
    );
    exportMarkdownReport(content, `desensitization-report-${Date.now()}.md`);
  }

  // 导出 PDF (截图方式，保证样式)
  async function exportPDF() {
    const element = getResultsPanelElement();
    if (!element) return;

    loading.value = true;
    try {
      await exportElementToPdf(
        element,
        `desensitization-report-${Date.now()}.pdf`,
      );
    } catch (err) {
      console.error("PDF Export failed", err);
      error.value = "PDF导出失败: " + err.message;
    } finally {
      loading.value = false;
    }
  }

  // 分享截图
  async function shareScreenshot() {
    const element = getResultsPanelElement();
    if (!element) return;

    loading.value = true;
    try {
      const action = await copyElementScreenshotOrDownload(
        element,
        `screenshot-${Date.now()}.png`,
      );
      if (action === "copied") {
        alert("截图已复制到剪贴板！");
      }
    } catch (err) {
      console.error("Screenshot failed", err);
      error.value = "截图失败: " + err.message;
    } finally {
      loading.value = false;
    }
  }

  // 清除所有内容
  function clearAll() {
    formData.prompt = "";
    formData.customParams = "";
    results.value = null;
    detectedEntities.value = [];
    error.value = "";
    processingStep.value = "";
    removeFile();
  }

  // 获取当前选择的策略信息
  function getCurrentStrategy() {
    return (
      strategies.find((s) => s.value === desensitizationStrategy.value) ||
      strategies[0]
    );
  }

  // 获取当前选择的提供商信息
  function getCurrentProvider() {
    return (
      providers.value.find((p) => p.value === llmProvider.value) ||
      providers.value[0] || { label: llmProvider.value, icon: "⚪" }
    );
  }

  // 文本格式化：将转义的换行/制表符还原为真实字符
  const formatText = (text) => {
    const s = text ?? "";
    return s
      .replaceAll("\\r\\n", "\n")
      .replaceAll("\\n", "\n")
      .replaceAll("\\t", "\t");
  };

  // 提取并格式化要展示的LLM文本
  const getLlmText = () => {
    const r = results.value?.llmResponse;
    if (!r) return "";
    if (r.success === false) {
      const msg = r.errorMessage ?? "LLM服务调用失败";
      return formatText(msg);
    }
    const text =
      r.desensitizedResponse ?? r.originalResponse ?? r.content ?? "";
    return formatText(text);
  };

  // 前端策略值映射为后端策略名
  function mapStrategyToBackend(val) {
    switch (val) {
      case "MASKED":
        return "maskDesensitizationStrategy";
      case "PARTIAL":
        return "partialDesensitizationStrategy";
      case "GENERALIZATION":
        return "generalizationDesensitizationStrategy";
      case "SEMANTIC":
        return "semanticDesensitizationStrategy";
      case "AUTO":
        return undefined;
    }
  }

  onMounted(() => {
    fetchProviders();
  });
</script>

<template>
  <div class="llm-desensitization">
    <h2 class="title">🔐 LLM智能脱敏助手</h2>
    <p class="subtitle">安全地与大语言模型交互，保护您的敏感信息</p>

    <!-- 输入区域 -->
    <div class="input-section">
      <!-- 配置选择 -->
      <div class="config-row">
        <!-- LLM提供商选择 -->
        <div class="config-group">
          <label for="provider-select">选择LLM服务:</label>
          <div class="provider-select-container">
            <select
              id="provider-select"
              v-model="llmProvider"
              class="provider-select"
            >
              <option
                v-for="provider in providers"
                :key="provider.value"
                :value="provider.value"
              >
                {{ provider.icon }} {{ provider.label }}
              </option>
            </select>
          </div>
        </div>

        <!-- 脱敏策略选择 -->
        <div class="config-group">
          <label for="strategy-select">脱敏策略:</label>
          <div class="strategy-select-container">
            <select
              id="strategy-select"
              v-model="desensitizationStrategy"
              class="strategy-select"
            >
              <option
                v-for="strategy in strategies"
                :key="strategy.value"
                :value="strategy.value"
              >
                {{ strategy.icon }} {{ strategy.label }}
              </option>
            </select>
          </div>
        </div>

        <!-- 情景感知开关已移动到侧边栏 -->

        <!-- 手动指定情景类型 -->
        <div
          class="config-group"
          v-if="props.scenarioSettings.autoScenario"
        >
          <label for="scenario-type">手动情景类型（可选）:</label>
          <div class="strategy-select-container">
            <select
              id="scenario-type"
              v-model="manualScenarioType"
              class="strategy-select"
            >
              <option
                v-for="s in scenarioTypes"
                :key="s.value"
                :value="s.value"
              >
                {{ s.label }}
              </option>
            </select>
          </div>
        </div>
      </div>

      <!-- 策略说明 -->
      <div
        class="strategy-info"
        v-if="getCurrentStrategy()"
      >
        <div class="strategy-header">
          <span class="strategy-icon">{{ getCurrentStrategy().icon }}</span>
          <span class="strategy-name">{{ getCurrentStrategy().label }}</span>
        </div>
        <p class="strategy-description">
          {{ getCurrentStrategy().description }}
        </p>
        <small class="strategy-example"
          >示例: {{ getCurrentStrategy().example }}</small
        >
      </div>

      <!-- 提示词输入 -->
      <div class="form-group">
        <label for="prompt-input"
          >输入提示词 (支持敏感信息自动检测和脱敏):</label
        >
        <textarea
          id="prompt-input"
          v-model="formData.prompt"
          placeholder="请输入您想发送给LLM的问题或指令，系统会自动检测并脱敏手机号、邮箱、身份证号等敏感信息"
          rows="8"
          class="prompt-textarea"
          :readonly="false"
          :disabled="false"
        ></textarea>
      </div>
      <div class="form-group">
        <label for="file-input">上传文件（可选）：</label>
        <input
          id="file-input"
          type="file"
          class="file-input-custom"
          accept=".txt,.json,.docx,.xlsx,.pdf"
          @change="onFileChange"
        />
        <div
          v-if="selectedFileName"
          class="file-info"
        >
          <span class="file-name">{{ selectedFileName }}</span>
          <span class="file-type">{{ selectedFileType }}</span>
          <button
            class="btn btn-secondary"
            :disabled="fileLoading"
            @click="removeFile"
          >
            移除附件
          </button>
        </div>
      </div>

      <!-- 操作按钮 -->
      <div class="action-buttons">
        <button
          @click="processWithDesensitization"
          :disabled="loading || fileLoading || !formData.prompt.trim()"
          class="btn btn-primary"
        >
          <span
            v-if="loading"
            class="loading-spinner"
          ></span>
          {{ loading ? "处理中..." : "安全发送到" }}
          {{ getCurrentProvider().label }}
        </button>
        <button
          @click="clearAll"
          :disabled="loading"
          class="btn btn-secondary"
        >
          清空
        </button>
      </div>

      <!-- 处理状态 -->
      <LlmProcessingProgress
        :current-percent="currentPercent"
        :processing-step="processingStep"
      />

      <!-- 错误信息 -->
      <div
        v-if="error"
        class="error-message"
      >
        ❌ {{ error }}
      </div>
    </div>

    <!-- 检测结果展示 -->
    <LlmDetectionResults
      :detected-entities="detectedEntities"
      :collapsed-detection="collapsedDetection"
      @toggle-collapse="collapsedDetection = !collapsedDetection"
    />

    <LlmResultsPanel
      v-if="results"
      ref="resultsPanelRef"
      :results="results"
      :displayed-llm-text="displayedLlmText"
      :is-typing="isTyping"
      :highlighted-original-html="getHighlightedOriginal()"
      :desensitized-prompt="formatText(results.desensitizedPrompt)"
      :llm-text="getLlmText()"
      :provider-icon="getCurrentProvider().icon"
      :loading="loading"
      @copy="copyToClipboard"
      @export-markdown="exportMarkdown"
      @export-pdf="exportPDF"
      @share-screenshot="shareScreenshot"
    />

    <!-- 特性说明 -->
    <LlmFeatureCards />
  </div>
</template>

<style scoped>
  .llm-desensitization {
    width: 100%;
    margin: 0;
    padding: 20px;
    font-family:
      system-ui,
      -apple-system,
      BlinkMacSystemFont,
      "Segoe UI",
      Roboto,
      sans-serif;
  }

  .title {
    color: #646cff;
    text-align: center;
    margin-bottom: 10px;
    font-size: 2.5em;
  }

  .subtitle {
    text-align: center;
    color: #6b7280;
    margin-bottom: 30px;
    font-size: 1.1em;
  }

  .input-section {
    background: var(--input-bg);
    border-radius: 12px;
    padding: 25px;
    margin-bottom: 30px;
    box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
    border: 1px solid var(--border);
  }

  .config-row {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 20px;
    margin-bottom: 20px;
  }

  @media (max-width: 768px) {
    .config-row {
      grid-template-columns: 1fr;
    }
  }

  .config-group {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .config-group label {
    font-weight: 600;
    color: var(--text);
    font-size: 0.9em;
  }

  .provider-select,
  .strategy-select {
    padding: 10px 12px;
    border-radius: 8px;
    border: 1px solid var(--border);
    background: var(--card-bg);
    color: var(--text);
    font-size: 1em;
    cursor: pointer;
    transition: all 0.3s ease;
  }

  .provider-select:hover,
  .strategy-select:hover {
    border-color: #646cff;
  }

  .strategy-info {
    background: rgba(100, 108, 255, 0.1);
    border: 1px solid rgba(100, 108, 255, 0.2);
    border-radius: 8px;
    padding: 15px;
    margin-bottom: 20px;
    width: 100%;
  }

  .strategy-header {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 8px;
  }

  .strategy-icon {
    font-size: 1.5em;
  }

  .strategy-name {
    font-weight: 600;
    color: #646cff;
  }

  .strategy-description {
    margin: 0 0 5px 0;
    color: #4b5563;
    font-size: 0.95em;
  }
  .strategy-example {
    color: #6b7280;
    font-style: italic;
  }

  .form-group {
    margin-bottom: 20px;
  }

  .form-group label {
    display: block;
    margin-bottom: 8px;
    color: var(--text);
    font-weight: 600;
  }

  .optional label {
    font-weight: 600;
    color: var(--text);
  }

  .prompt-textarea,
  .params-textarea {
    width: 100%;
    min-height: 120px;
    padding: 12px;
    border-radius: 8px;
    border: 1px solid var(--border);
    background: var(--card-bg);
    color: var(--text);
    font-family: inherit;
    font-size: 1em;
    resize: vertical;
    transition: all 0.3s ease;
    box-sizing: border-box;
    outline: none;
    z-index: 10;
    position: relative;
  }

  .params-textarea {
    min-height: 80px;
    font-family: "Courier New", monospace;
    font-size: 0.9em;
  }

  .prompt-textarea:focus,
  .params-textarea:focus {
    outline: none;
    border-color: #646cff;
    box-shadow: 0 0 0 3px rgba(100, 108, 255, 0.1);
    background: rgba(255, 255, 255, 0.2);
  }

  /* 增强placeholder样式 */
  .prompt-textarea::placeholder,
  .params-textarea::placeholder {
    color: #9ca3af;
    opacity: 1;
  }

  /* 确保输入框可以正常接收点击和焦点 */
  .prompt-textarea,
  .params-textarea {
    pointer-events: auto;
    cursor: text;
  }

  .action-buttons {
    display: flex;
    gap: 15px;
    margin-bottom: 15px;
  }

  .btn {
    padding: 12px 24px;
    border-radius: 8px;
    border: none;
    font-size: 1em;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.3s ease;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
  }

  .btn-primary {
    background: #646cff;
    color: white;
  }

  .btn-primary:hover:not(:disabled) {
    background: #535bf2;
    transform: translateY(-1px);
  }

  .btn-secondary {
    background: #4b5563;
    color: #e5e7eb;
  }

  .btn-secondary:hover:not(:disabled) {
    background: #6b7280;
  }

  .btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
    transform: none;
  }

  .loading-spinner {
    width: 16px;
    height: 16px;
    border: 2px solid transparent;
    border-top: 2px solid currentColor;
    border-radius: 50%;
    animation: spin 1s linear infinite;
  }

  @keyframes spin {
    0% {
      transform: rotate(0deg);
    }
    100% {
      transform: rotate(360deg);
    }
  }

  .processing-status {
    display: flex;
    align-items: center;
    gap: 10px;
    color: #646cff;
    font-weight: 500;
    margin-bottom: 15px;
  }

  .processing-visual {
    display: flex;
    flex-direction: column;
    gap: 10px;
    margin-bottom: 15px;
  }

  .stepper {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: nowrap;
    overflow-x: auto;
    white-space: nowrap;
  }

  .step-item {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .step-item .dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: var(--border);
  }

  .step-item.active .dot {
    background: var(--primary);
  }

  .step-item .label {
    color: var(--text);
    font-size: 0.9em;
  }

  .step-item .badge {
    background: var(--success);
    color: white;
    border-radius: 10px;
    padding: 2px 8px;
    font-size: 0.75em;
  }

  .step-item:not(.active) .label {
    color: var(--text-light);
    opacity: 0.7;
  }

  .step-line {
    flex: 0 0 32px;
    height: 2px;
    background: var(--border);
  }

  .step-line.active {
    background: var(--primary);
  }

  .progress-bar-container {
    width: 100%;
    height: 10px;
    background: var(--border);
    border-radius: 6px;
    overflow: hidden;
    box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.05);
  }

  .progress-bar {
    height: 100%;
    background: var(--primary);
    border-right: 1px solid var(--primary-hover);
    transition: width 0.3s ease;
  }

  .progress-info {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 0.85em;
    color: var(--text-light);
  }

  .step-indicator {
    width: 8px;
    height: 8px;
    background: #646cff;
    border-radius: 50%;
    animation: pulse 1.5s ease-in-out infinite;
  }

  @keyframes pulse {
    0% {
      opacity: 1;
      transform: scale(1);
    }
    50% {
      opacity: 0.5;
      transform: scale(1.2);
    }
    100% {
      opacity: 1;
      transform: scale(1);
    }
  }

  .error-message {
    color: #ef4444;
    background: rgba(239, 68, 68, 0.1);
    border: 1px solid rgba(239, 68, 68, 0.2);
    border-radius: 8px;
    padding: 12px;
    margin-bottom: 20px;
    font-weight: 500;
  }

  .detection-results {
    background: rgba(16, 185, 129, 0.1);
    border: 1px solid rgba(16, 185, 129, 0.2);
    border-radius: 12px;
    padding: 20px;
    margin-bottom: 30px;
  }

  .detection-results h3 {
    color: #10b981;
    margin-top: 0;
    margin-bottom: 15px;
  }

  .entities-list {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .entity-item {
    display: flex;
    align-items: center;
    gap: 15px;
    padding: 10px;
    background: rgba(255, 255, 255, 0.05);
    border-radius: 8px;
    font-size: 0.9em;
  }

  .entity-type {
    background: #10b981;
    color: white;
    padding: 4px 8px;
    border-radius: 4px;
    font-weight: 600;
    text-transform: uppercase;
    font-size: 0.8em;
  }

  .entity-original {
    font-family: "Courier New", monospace;
    color: #fbbf24;
    font-weight: 500;
    flex: 1;
  }

  .entity-position {
    color: #9ca3af;
    font-size: 0.8em;
  }

  .results-section {
    margin-top: 30px;
  }

  .results-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
  }

  .results-header h3 {
    color: #646cff;
    margin: 0;
  }

  .export-actions {
    display: flex;
    gap: 10px;
  }

  .section-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .comparison-section {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 20px;
    margin-bottom: 20px;
  }

  @media (max-width: 768px) {
    .comparison-section {
      grid-template-columns: 1fr;
    }
  }

  .comparison-panel {
    background: var(--card-bg);
    border-radius: 12px;
    overflow: hidden;
    border: 1px solid var(--border);
    box-shadow: var(--shadow);
    transition: all 0.3s ease;
  }

  .comparison-panel:hover {
    box-shadow:
      0 10px 15px -3px rgba(0, 0, 0, 0.1),
      0 4px 6px -2px rgba(0, 0, 0, 0.05);
  }

  .comparison-panel.original {
    /* Removed left border */
  }

  .comparison-panel.desensitized {
    /* Removed left border */
  }

  .panel-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 16px;
    background: var(--header-bg);
    border-bottom: 1px solid var(--border);
  }

  .panel-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .panel-header h4 {
    margin: 0;
    font-size: 0.95em;
    font-weight: 600;
    color: var(--text);
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .copy-btn {
    background: #f3f4f6;
    border: 1px solid #e5e7eb;
    cursor: pointer;
    padding: 6px 8px;
    border-radius: 6px;
    transition: all 0.2s ease;
    font-size: 1em;
    color: #6b7280;
  }

  .copy-btn:hover {
    background: #e5e7eb;
    color: #374151;
    transform: translateY(-1px);
    box-shadow: 0 1px 3px 0 rgba(0, 0, 0, 0.1);
  }

  .toggle-btn {
    background: #f3f4f6;
    border: 1px solid #e5e7eb;
    cursor: pointer;
    padding: 6px 8px;
    border-radius: 6px;
    transition: all 0.2s ease;
    font-size: 1em;
    color: #6b7280;
    margin-left: 8px;
  }

  .toggle-btn:hover {
    background: #e5e7eb;
    color: #374151;
    transform: translateY(-1px);
    box-shadow: 0 1px 3px 0 rgba(0, 0, 0, 0.1);
  }

  .content-display {
    margin: 0;
    padding: 16px;
    background: var(--card-bg);
    max-height: 200px;
    overflow-y: auto;
    font-family: "Courier New", monospace;
    font-size: 0.9em;
    line-height: 1.5;
    color: var(--text);
    white-space: pre-wrap;
    word-break: break-word;
    border-radius: 0 0 12px 12px;
  }

  .llm-response-panel {
    background: var(--card-bg);
    border-radius: 12px;
    overflow: hidden;
    border: 1px solid var(--border);
    box-shadow: var(--shadow);
    transition: all 0.3s ease;
  }

  .llm-response-panel:hover {
    box-shadow:
      0 10px 15px -3px rgba(0, 0, 0, 0.1),
      0 4px 6px -2px rgba(0, 0, 0, 0.05);
  }

  .llm-response-content {
    padding: 16px;
    background: var(--card-bg);
    max-height: 400px;
    overflow-y: auto;
  }

  .llm-response-content pre {
    margin: 0;
    background: var(--card-bg);
    font-family: "Courier New", monospace;
    font-size: 0.9em;
    line-height: 1.6;
    color: var(--text);
    white-space: pre-wrap;
    word-break: break-word;
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 12px;
  }

  .performance-info {
    background: var(--header-bg);
    padding: 12px 16px;
    border-top: 1px solid var(--border);
    text-align: right;
    color: var(--text-light);
    font-size: 0.85em;
    border-radius: 0 0 12px 12px;
  }

  .features-section {
    margin-top: 40px;
    background: var(--features-bg);
    border-radius: 12px;
    padding: 10px;
  }

  .features-section h3 {
    text-align: center;
    color: #646cff;
    margin-bottom: 30px;
  }

  .feature-cards {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
    gap: 20px;
  }

  .feature-card {
    background: var(--feature-card-bg);
    border-radius: 12px;
    padding: 25px;
    text-align: center;
    border: 1px solid var(--feature-card-border);
    transition: all 0.3s ease;
  }

  .feature-card:hover {
    transform: translateY(-5px);
    box-shadow: 0 10px 20px rgba(0, 0, 0, 0.1);
    border-color: var(--primary-hover);
  }

  .feature-icon {
    font-size: 2.5em;
    margin-bottom: 15px;
    color: #646cff;
  }

  .feature-card h4 {
    margin: 0 0 10px 0;
    color: #1a365d;
  }

  .feature-card p {
    margin: 0;
    color: #9ca3af;
    font-size: 0.9em;
    line-height: 1.5;
  }

  /* 响应式设计 */
  @media (max-width: 640px) {
    .llm-desensitization {
      padding: 10px;
    }

    .title {
      font-size: 2em;
    }

    .input-section {
      padding: 15px;
    }

    .action-buttons {
      flex-direction: column;
    }

    .btn {
      width: 100%;
    }
  }

  /* 亮色模式支持 */
  :global(.light) .llm-desensitization {
    color: #1f2937;
  }

  :global(.light) .input-section {
    background: #f9fafb;
    border-color: #e5e7eb;
  }

  :global(.light) .config-group label,
  :global(.light) .form-group label {
    color: #374151;
  }

  :global(.light) .provider-select,
  :global(.light) .strategy-select,
  :global(.light) .prompt-textarea,
  :global(.light) .params-textarea {
    background: var(--card-bg);
    color: var(--text);
    border-color: var(--border);
  }

  :global(.light) .comparison-panel,
  :global(.light) .llm-response-panel,
  :global(.light) .feature-card {
    background: #f9fafb;
    border-color: #e5e7eb;
  }

  :global(.light) .content-display,
  :global(.light) .llm-response-content pre {
    background: white;
    color: black;
  }
  .file-info {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 8px;
  }
  .file-name {
    color: #1f2937;
  }
  .file-type {
    color: #6b7280;
  }

  .label-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 8px;
  }

  .btn-text {
    background: none;
    border: none;
    color: var(--primary);
    font-size: 0.9em;
    cursor: pointer;
    padding: 4px 8px;
    border-radius: 4px;
    transition: background 0.2s;
  }

  .btn-text:hover {
    background: rgba(59, 130, 246, 0.1);
  }

  .cursor-blink {
    display: inline-block;
    width: 2px;
    height: 1.2em;
    background-color: var(--text);
    margin-left: 2px;
    vertical-align: middle;
    animation: blink 1s step-end infinite;
  }

  @keyframes blink {
    0%,
    100% {
      opacity: 1;
    }
    50% {
      opacity: 0;
    }
  }

  /* 敏感词高亮样式 */
  :deep(.entity-highlight) {
    border-radius: 3px;
    padding: 0 2px;
    margin: 0 1px;
    font-weight: 500;
    cursor: help;
    border-bottom: 2px solid transparent;
  }

  :deep(.type-phone) {
    background-color: rgba(251, 191, 36, 0.3);
    border-bottom-color: #f59e0b;
  }
  :deep(.type-id_card) {
    background-color: rgba(239, 68, 68, 0.2);
    border-bottom-color: #ef4444;
  }
  :deep(.type-email) {
    background-color: rgba(59, 130, 246, 0.2);
    border-bottom-color: #3b82f6;
  }
  :deep(.type-bank_card) {
    background-color: rgba(16, 185, 129, 0.2);
    border-bottom-color: #10b981;
  }
  :deep(.type-address) {
    background-color: rgba(139, 92, 246, 0.2);
    border-bottom-color: #8b5cf6;
  }
  :deep(.type-plate_number) {
    background-color: rgba(236, 72, 153, 0.2);
    border-bottom-color: #ec4899;
  }
  :deep(.type-ipv4),
  :deep(.type-ipv6) {
    background-color: rgba(107, 114, 128, 0.2);
    border-bottom-color: #6b7280;
  }
  :deep(.type-name) {
    background-color: rgba(249, 115, 22, 0.2);
    border-bottom-color: #f97316;
  }
  :deep(.type-organization) {
    background-color: rgba(6, 182, 212, 0.2);
    border-bottom-color: #06b6d4;
  }
  :deep(.type-date) {
    background-color: rgba(100, 116, 139, 0.2);
    border-bottom-color: #64748b;
  }
  :deep(.type-passport) {
    background-color: rgba(99, 102, 241, 0.2);
    border-bottom-color: #6366f1;
  }
  :deep(.type-mobile_phone) {
    background-color: rgba(251, 191, 36, 0.3);
    border-bottom-color: #f59e0b;
  }
  :deep(.type-landline) {
    background-color: rgba(251, 191, 36, 0.3);
    border-bottom-color: #f59e0b;
  }
  :deep(.type-license_plate) {
    background-color: rgba(236, 72, 153, 0.2);
    border-bottom-color: #ec4899;
  }
  :deep(.type-company) {
    background-color: rgba(6, 182, 212, 0.2);
    border-bottom-color: #06b6d4;
  }
  :deep(.type-location) {
    background-color: rgba(139, 92, 246, 0.2);
    border-bottom-color: #8b5cf6;
  }
  :deep(.type-phone_number) {
    background-color: rgba(251, 191, 36, 0.3);
    border-bottom-color: #f59e0b;
  }
  :deep(.type-api_key) {
    background-color: rgba(217, 70, 239, 0.2);
    border-bottom-color: #d946ef;
  }
  :deep(.type-ip_address) {
    background-color: rgba(14, 165, 233, 0.2);
    border-bottom-color: #0ea5e9;
  }
  :deep(.type-credit_card) {
    background-color: rgba(234, 179, 8, 0.2);
    border-bottom-color: #eab308;
  }
  :deep(.type-custom) {
    background-color: rgba(168, 162, 158, 0.2);
    border-bottom-color: #a8a29e;
  }
  :deep(.type-social_security) {
    background-color: rgba(244, 63, 94, 0.2);
    border-bottom-color: #f43f5e;
  }
  :deep(.type-birth_date) {
    background-color: rgba(100, 116, 139, 0.2);
    border-bottom-color: #64748b;
  }
  :deep(.type-password) {
    background-color: rgba(255, 0, 0, 0.2);
    border-bottom-color: #ff0000;
  }

  .analysis-section {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 20px;
    margin-bottom: 30px;
    animation: slideDown 0.5s ease;
  }

  .analysis-card {
    background: var(--card-bg);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 20px;
    box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
  }

  .detection-results {
    /* Reset previous margins/background as it's now inside a card */
    margin-bottom: 0;
    background: transparent;
    border: none;
    padding: 0;
    box-shadow: none;
  }

  .stats-panel {
    display: flex;
    flex-direction: column;
    justify-content: center;
  }

  @media (max-width: 960px) {
    .analysis-section {
      grid-template-columns: 1fr;
    }
  }

  @keyframes slideDown {
    from {
      opacity: 0;
      transform: translateY(-10px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }

  .file-input-custom {
    width: 100%;
    padding: 8px;
    background: var(--card-bg);
    border: 1px solid var(--border);
    border-radius: 8px;
    color: var(--text);
    font-size: 0.95em;
    transition: border-color 0.2s;
  }

  .file-input-custom:hover {
    border-color: #646cff;
  }

  .file-input-custom::file-selector-button {
    margin-right: 15px;
    padding: 8px 16px;
    border-radius: 6px;
    border: none;
    background: #646cff;
    color: white;
    cursor: pointer;
    transition: all 0.2s;
    font-weight: 500;
    font-family: inherit;
  }

  .file-input-custom::file-selector-button:hover {
    background: #535bf2;
  }
</style>
<style>
  :global(.dark) .llm-desensitization {
    color: var(--text);
  }

  :global(.dark) .input-section {
    background: rgba(17, 24, 39, 0.6);
    border-color: var(--border);
  }

  :global(.dark) .config-group label,
  :global(.dark) .form-group label {
    color: var(--text);
  }

  :global(.dark) .provider-select,
  :global(.dark) .strategy-select,
  :global(.dark) .prompt-textarea,
  :global(.dark) .params-textarea {
    background: rgba(17, 24, 39, 0.6);
    color: var(--text);
    border-color: var(--border);
  }

  :global(.dark) .comparison-panel,
  :global(.dark) .llm-response-panel,
  :global(.dark) .feature-card {
    background: var(--card-bg);
    border-color: var(--border);
  }

  :global(.dark) .panel-header {
    background: rgba(30, 41, 59, 0.8);
    border-bottom-color: var(--border);
  }

  :global(.dark) .content-display,
  :global(.dark) .llm-response-content pre {
    background: var(--card-bg);
    color: var(--text);
    border-color: var(--border);
  }

  :global(.dark) .processing-status {
    color: var(--primary);
  }
  :global(.dark) .copy-btn {
    background: #1f2937;
    border-color: var(--border);
    color: var(--text-light);
  }
  :global(.dark) .copy-btn:hover {
    background: #111827;
    color: var(--text);
  }
  :global(.dark) .toggle-btn {
    background: #1f2937;
    border-color: var(--border);
    color: var(--text-light);
  }
  :global(.dark) .toggle-btn:hover {
    background: #111827;
    color: var(--text);
  }
  :global(.dark) .title {
    color: var(--primary);
  }

  :global(.dark) .panel-header h4 {
    color: var(--text);
  }

  :global(.dark) .strategy-description,
  :global(.dark) .strategy-example {
    color: var(--text-light);
  }

  :global(.dark) .prompt-textarea::placeholder,
  :global(.dark) .params-textarea::placeholder {
    color: #cbd5e1;
  }

  :global(.dark) .feature-card h4 {
    color: var(--text);
  }

  :global(.dark) .feature-card p {
    color: var(--text-light);
  }

  /* Markdown 样式 */
  .markdown-body {
    font-family:
      -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial,
      sans-serif, "Apple Color Emoji", "Segoe UI Emoji";
    font-size: 16px;
    line-height: 1.5;
    word-wrap: break-word;
    color: var(--text);
  }

  .markdown-body h1,
  .markdown-body h2,
  .markdown-body h3,
  .markdown-body h4,
  .markdown-body h5,
  .markdown-body h6 {
    margin-top: 24px;
    margin-bottom: 16px;
    font-weight: 600;
    line-height: 1.25;
  }

  .markdown-body h1 {
    font-size: 2em;
    padding-bottom: 0.3em;
    border-bottom: 1px solid var(--border);
  }
  .markdown-body h2 {
    font-size: 1.5em;
    padding-bottom: 0.3em;
    border-bottom: 1px solid var(--border);
  }
  .markdown-body h3 {
    font-size: 1.25em;
  }
  .markdown-body h4 {
    font-size: 1em;
  }

  .markdown-body p {
    margin-top: 0;
    margin-bottom: 16px;
  }

  .markdown-body blockquote {
    margin: 0;
    margin-bottom: 16px;
    padding: 0 1em;
    color: var(--text-light);
    border-left: 0.25em solid var(--border);
  }

  .markdown-body ul,
  .markdown-body ol {
    margin-top: 0;
    margin-bottom: 16px;
    padding-left: 2em;
  }

  .markdown-body pre {
    padding: 16px;
    overflow: auto;
    font-size: 85%;
    line-height: 1.45;
    background-color: var(--card-bg);
    border-radius: 6px;
    border: 1px solid var(--border);
    margin-bottom: 16px;
  }

  .markdown-body code {
    padding: 0.2em 0.4em;
    margin: 0;
    font-size: 85%;
    background-color: rgba(175, 184, 193, 0.2);
    border-radius: 6px;
    font-family:
      ui-monospace,
      SFMono-Regular,
      SF Mono,
      Menlo,
      Consolas,
      Liberation Mono,
      monospace;
  }

  .markdown-body pre code {
    display: inline;
    padding: 0;
    margin: 0;
    overflow: visible;
    line-height: inherit;
    word-wrap: normal;
    background-color: transparent;
    border: 0;
  }

  .markdown-body table {
    display: block;
    width: 100%;
    width: max-content;
    max-width: 100%;
    overflow: auto;
    border-spacing: 0;
    border-collapse: collapse;
    margin-bottom: 16px;
  }

  .markdown-body table th,
  .markdown-body table td {
    padding: 6px 13px;
    border: 1px solid var(--border);
  }

  .markdown-body table tr {
    background-color: var(--card-bg);
    border-top: 1px solid var(--border);
  }

  .markdown-body table tr:nth-child(2n) {
    background-color: rgba(127, 127, 127, 0.05);
  }

  .markdown-body img {
    max-width: 100%;
    box-sizing: content-box;
    background-color: var(--card-bg);
  }

  .markdown-body hr {
    height: 0.25em;
    padding: 0;
    margin: 24px 0;
    background-color: var(--border);
    border: 0;
  }

  /* 链接样式 */
  .markdown-body a {
    color: var(--primary);
    text-decoration: none;
  }
  .markdown-body a:hover {
    text-decoration: underline;
  }
</style>
