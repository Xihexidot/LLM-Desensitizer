import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return
          }

          if (id.includes('html2canvas') || id.includes('jspdf')) {
            return 'export-tools'
          }

          if (id.includes('markdown-it') || id.includes('highlight.js')) {
            return 'markdown-tools'
          }

          if (id.includes('chart.js') || id.includes('vue-chartjs')) {
            return 'chart-tools'
          }

          if (id.includes('/vue/')) {
            return 'vue-vendor'
          }
        }
      }
    }
  }
})
