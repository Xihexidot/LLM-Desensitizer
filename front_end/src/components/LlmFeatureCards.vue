<template>
  <div class="features-section">
    <h3>✨ 功能特性</h3>
    <div class="feature-cards">
      <div class="feature-card">
        <div class="feature-icon">🛡️</div>
        <h4>多重脱敏策略</h4>
        <p>支持智能自动、标识替换、部分保留、语义泛化/替换四种策略，满足不同场景需求</p>
      </div>
      <div class="feature-card">
        <div class="feature-icon">🤖</div>
        <h4>多模型支持</h4>
        <p>无缝集成OpenAI、DeepSeek、Doubao等多种大语言模型服务</p>
      </div>
      <div class="feature-card">
        <div class="feature-icon">🔍</div>
        <h4>敏感信息检测</h4>
        <p>自动识别手机号、邮箱、身份证号等多种敏感信息类型</p>
      </div>
      <div class="feature-card">
        <div class="feature-icon">📊</div>
        <h4>实时对比</h4>
        <p>直观展示原始内容和脱敏后的对比，让您清楚了解保护效果</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
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
  background: var(--card-bg);
  border-radius: 12px;
  padding: 24px 20px;
  border: 1px solid var(--border);
  text-align: center;
}

.feature-icon {
  font-size: 2em;
  margin-bottom: 12px;
}

.feature-card h4 {
  margin: 0 0 10px 0;
  color: var(--text);
}

.feature-card p {
  margin: 0;
  color: var(--text-light);
  line-height: 1.6;
}
</style>
