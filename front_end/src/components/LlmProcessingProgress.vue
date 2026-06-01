<script setup>
defineProps({
  currentPercent: {
    type: Number,
    required: true
  },
  processingStep: {
    type: String,
    default: ''
  }
})
</script>

<template>
  <div v-if="processingStep" class="processing-visual">
    <div
      class="progress-bar-container"
      role="progressbar"
      :aria-valuenow="currentPercent"
      aria-valuemin="0"
      aria-valuemax="100"
    >
      <div class="progress-bar" :style="{ width: currentPercent + '%' }"></div>
    </div>
    <div class="progress-info">
      <span>进度: {{ currentPercent }}%</span>
      <span>当前步骤: {{ processingStep }}</span>
    </div>
  </div>
</template>

<style scoped>
.processing-visual {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 15px;
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
</style>
