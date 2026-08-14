/**
 * decode-util.js 单元测试（脱敏解码 + 行级噪声过滤）
 *
 * 使用 Node 内置测试框架运行：node --test tests/decode-util.unit.js
 * 覆盖：
 *  1. filterNoiseLines：剔除系统提示 / 功能按钮 / 时间标记 / 脱敏提示等噪声行，
 *     保留核心对话行，并折叠连续重复行；
 *  2. decodeWithHighlights：解码后按行过滤，含脱敏标记的行保留、噪声行剔除。
 */
const { test } = require("node:test");
const assert = require("node:assert");

const decoder = require("../decode-util.js");

test("filterNoiseLines：剔除系统提示与免责声明", () => {
  const text = [
    "我的手机号是 13800138000。",
    "本回答由 AI 生成，内容仅供参考，请仔细甄别",
    "以上内容由 AI 生成，仅供学习参考",
    "请核对信息。",
  ].join("\n");
  assert.strictEqual(
    decoder.filterNoiseLines(text),
    "我的手机号是 13800138000。\n请核对信息。",
  );
});

test("filterNoiseLines：剔除功能按钮与脱敏提示文字", () => {
  const text = [
    "完整原始内容（已还原 33 处脱敏标记）",
    "已还原 2 处脱敏标记",
    "复制原文",
    "关闭",
    "开启新对话",
    "置顶",
    "一键复原",
    "核心对话内容保持不变。",
  ].join("\n");
  assert.strictEqual(decoder.filterNoiseLines(text), "核心对话内容保持不变。");
});

test("filterNoiseLines：剔除时间标记与纯符号装饰行", () => {
  const text = [
    "14:30",
    "昨天 21:15",
    "2026-08-13 14:30",
    "3月15日 09:12",
    "-----",
    "正文内容。",
  ].join("\n");
  assert.strictEqual(decoder.filterNoiseLines(text), "正文内容。");
});

test("filterNoiseLines：剔除 DeepSeek 思考/联网搜索/平台名标签模式残留", () => {
  const text = [
    "已深度思考（用时 12 秒）",
    "深度思考",
    "思考过程",
    "展开思考过程",
    "收起思考过程",
    "联网搜索",
    "已搜索到 5 个网页",
    "DeepSeek",
    "ChatGPT",
    "AI 回复：您的银行卡 [BANK_CARD_1] 当前余额为 8,520.00 元。",
  ].join("\n");
  assert.strictEqual(
    decoder.filterNoiseLines(text),
    "AI 回复：您的银行卡 [BANK_CARD_1] 当前余额为 8,520.00 元。",
  );
});

test("isNoiseLine：命中 DeepSeek 模式残留与平台名标签", () => {
  assert.strictEqual(decoder.isNoiseLine("已深度思考"), true);
  assert.strictEqual(decoder.isNoiseLine("已深度思考（用时 12 秒）"), true);
  assert.strictEqual(decoder.isNoiseLine("联网搜索"), true);
  assert.strictEqual(decoder.isNoiseLine("已搜索到 3 个网页"), true);
  assert.strictEqual(decoder.isNoiseLine("DeepSeek"), true);
  assert.strictEqual(decoder.isNoiseLine("Kimi"), true);
  // 平台名出现在行内（如"DeepSeek 的回答如下"）不按整行模式误判
  assert.strictEqual(decoder.isNoiseLine("DeepSeek 的回答如下"), false);
  assert.strictEqual(decoder.isNoiseLine("思考一下这个问题的解法"), false);
});

test("filterNoiseLines：保留核心对话行，不误伤正文", () => {
  const text = [
    "用户：请帮我查询银行卡余额",
    "AI 回复：您的余额为 5,200.00 元。",
    "14:30 需要提醒您注意账户安全", // 时间后跟正文 → 不算整行时间戳，保留
  ].join("\n");
  assert.strictEqual(
    decoder.filterNoiseLines(text),
    "用户：请帮我查询银行卡余额\nAI 回复：您的余额为 5,200.00 元。\n14:30 需要提醒您注意账户安全",
  );
});

test("filterNoiseLines：折叠连续重复行，不影响普通行", () => {
  const text = [
    "系统提示：网络环境正常",
    "系统提示：网络环境正常",
    "第一行正文",
    "第一行正文",
    "第二行正文",
  ].join("\n");
  assert.strictEqual(
    decoder.filterNoiseLines(text),
    "系统提示：网络环境正常\n第一行正文\n第二行正文",
  );
});

test("filterNoiseLines：空输入安全返回空串", () => {
  assert.strictEqual(decoder.filterNoiseLines(null), "");
  assert.strictEqual(decoder.filterNoiseLines(""), "");
  assert.strictEqual(decoder.filterNoiseLines("   \n  "), "");
});

test("isNoiseLine：命中与未命中", () => {
  assert.strictEqual(decoder.isNoiseLine("一键复原"), true);
  assert.strictEqual(
    decoder.isNoiseLine("本回答由 AI 生成，内容仅供参考，请仔细甄别"),
    true,
  );
  assert.strictEqual(decoder.isNoiseLine("请帮我查询银行卡余额"), false);
});

test("decodeWithHighlights：解码后按行过滤，噪声行剔除、含标记行保留", () => {
  const mapping = {
    "[PHONE_1]": "13800138000",
    "[ID_CARD_1]": "33010219900307663X",
  };
  const text = [
    "AI 回复：已收到您的信息（[PHONE_1]、[ID_CARD_1]），我们将为您妥善处理。",
    "本回答由 AI 生成，内容仅供参考，请仔细甄别",
    "2026-08-13 14:30",
  ].join("\n");
  const result = decoder.decodeWithHighlights(text, mapping);

  // 核心对话行完整还原，噪声行不进入结果
  assert.strictEqual(
    result.text,
    "AI 回复：已收到您的信息（13800138000、33010219900307663X），我们将为您妥善处理。",
  );
  assert.ok(result.html.includes("<mark>13800138000</mark>"));
  assert.ok(result.html.includes("<mark>33010219900307663X</mark>"));
  assert.ok(!result.html.includes("本回答由 AI 生成"));
  assert.ok(!result.html.includes("2026-08-13"));
  assert.strictEqual(result.replacedCount, 2);
});

test("decodeWithHighlights：不含映射时仍过滤噪声行，保留核心内容", () => {
  const text = "核心内容。\n一键复原\n请核对。";
  const result = decoder.decodeWithHighlights(text, {});
  assert.strictEqual(result.text, "核心内容。\n请核对。");
  assert.strictEqual(result.replacedCount, 0);
});
