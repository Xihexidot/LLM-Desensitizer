// 全局配置文件

// API 基础 URL
// 开发环境通常为 http://localhost:8080
// 生产环境可以通过构建环境变量 VITE_API_BASE_URL 覆盖（便于适配企业内部部署地址）
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

// 其他全局配置可以在这里添加
export const DEFAULT_LANGUAGE = 'zh';
