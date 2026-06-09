---
alwaysApply: true
scene: git_message
---


生成符合 Conventional Commits 规范的提交信息。

格式：
<type>(<scope>): <中文描述>

要求：
- 描述简洁明确
- 不超过 50 个字
- 不添加 emoji
- 不添加 AI 生成说明
- 根据代码变更自动推断 scope
- 根据变更内容自动推断：
   feat, fix, refactor, perf, docs, test, build, ci, chore, revert
- 聚焦“为什么改”和“实现了什么”，避免简单描述文件操作。