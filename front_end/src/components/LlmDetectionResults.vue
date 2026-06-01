<script setup>
import DashboardStats from './DashboardStats.vue'

defineProps({
  detectedEntities: {
    type: Array,
    default: () => []
  },
  collapsedDetection: {
    type: Boolean,
    default: false
  }
})

defineEmits(['toggle-collapse'])
</script>

<template>
  <div v-if="detectedEntities.length > 0" class="analysis-section">
    <div class="analysis-card stats-panel">
      <DashboardStats :detectedEntities="detectedEntities" />
    </div>

    <div class="analysis-card detection-results">
      <div class="section-header">
        <h3>🔍 检测到的敏感信息</h3>
        <div class="panel-actions">
          <button
            class="toggle-btn"
            title="收起/展开敏感信息列表"
            @click="$emit('toggle-collapse')"
          >
            {{ collapsedDetection ? '⬇️ 展开' : '⬆️ 收起' }}
          </button>
        </div>
      </div>
      <div v-show="!collapsedDetection" class="entities-list">
        <div
          v-for="(entity, index) in detectedEntities"
          :key="index"
          class="entity-item"
          :class="`entity-${entity.type.toLowerCase()}`"
        >
          <span class="entity-type">{{ entity.type }}</span>
          <span class="entity-original">{{ entity.originalText }}</span>
          <span class="entity-position">{{ entity.start }}-{{ entity.end }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.analysis-section {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(380px, 2fr);
  gap: 20px;
  margin-bottom: 30px;
}

.analysis-card {
  background: var(--card-bg);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.section-header h3 {
  margin: 0;
  color: #646cff;
}

.entities-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 420px;
  overflow-y: auto;
}

.entity-item {
  display: grid;
  grid-template-columns: 140px 1fr auto;
  gap: 12px;
  align-items: center;
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--input-bg);
  border: 1px solid var(--border);
}

.entity-type {
  font-weight: 700;
  color: #646cff;
}

.entity-original {
  overflow-wrap: anywhere;
  color: var(--text);
}

.entity-position {
  color: var(--text-light);
  font-size: 0.9em;
}

.toggle-btn {
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--card-bg);
  color: var(--text-light);
  cursor: pointer;
  transition: all 0.2s ease;
}

.toggle-btn:hover {
  border-color: #646cff;
  color: var(--text);
}

@media (max-width: 960px) {
  .analysis-section {
    grid-template-columns: 1fr;
  }

  .entity-item {
    grid-template-columns: 1fr;
  }
}
</style>
