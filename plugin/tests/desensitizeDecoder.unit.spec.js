/**
 * 前端脱敏解码工具单元测试（纯逻辑，无需浏览器）。
 *
 * 覆盖：
 *  1. decodeText：精准还原 [TYPE_1] / [MASKED_1] 脱敏标记，未命中的 "[...]" 保持原样；
 *  2. buildMaskMapping：基于脱敏文本与实体列表兜底重建"占位符 → 明文"映射，
 *     序号分配与后端一致（同类型按原文位置 start 降序分配），并处理去重与部分脱敏值。
 */
const { test, expect } = require("@playwright/test");

let decoder;

test.beforeAll(async () => {
  // 前端项目 package.json 声明 "type": "module"，Node 动态 import 可直接加载其 ESM 模块
  decoder = await import("../../front_end/src/utils/desensitizeDecoder.js");
});

test.describe("decodeText 解码还原", () => {
  test("精准还原 [PHONE_1]、[MASKED_1] 等脱敏标记为原始数据", () => {
    const mapping = {
      "[PHONE_1]": "13800138000",
      "[MASKED_1]": "33010219900307663X",
    };
    const text =
      "您的联系方式是[PHONE_1]，证件号[MASKED_1]，请核对。";
    expect(decoder.decodeText(text, mapping)).toBe(
      "您的联系方式是13800138000，证件号33010219900307663X，请核对。",
    );
  });

  test("同一标记多次出现全部还原", () => {
    const mapping = { "[PHONE_1]": "13800138000" };
    expect(decoder.decodeText("[PHONE_1]和[PHONE_1]", mapping)).toBe(
      "13800138000和13800138000",
    );
  });

  test("未命中映射的方括号片段保持原样，避免误替换", () => {
    const mapping = { "[PHONE_1]": "13800138000" };
    const text = "号码[PHONE_1]，系统提示[请确认身份]";
    expect(decoder.decodeText(text, mapping)).toBe(
      "号码13800138000，系统提示[请确认身份]",
    );
  });

  test("空文本或空映射安全返回", () => {
    expect(decoder.decodeText(null, { "[PHONE_1]": "13800138000" })).toBeNull();
    expect(decoder.decodeText("hello", {})).toBe("hello");
    expect(decoder.decodeText("hello", null)).toBe("hello");
  });
});

test.describe("buildMaskMapping 兜底映射重建", () => {
  test("按类型与序号建立映射（后端序号按 start 降序分配）", () => {
    const mapping = decoder.buildMaskMapping(
      "电话[PHONE_1]与[PHONE_2]",
      [
        { type: "PHONE_NUMBER", originalText: "13800000001", start: 3, end: 14 },
        { type: "PHONE_NUMBER", originalText: "13900000002", start: 17, end: 28 },
      ],
    );
    // start 降序：13900000002 在前 → [PHONE_1]
    expect(mapping["[PHONE_1]"]).toBe("13900000002");
    expect(mapping["[PHONE_2]"]).toBe("13800000001");
  });

  test("同类型多类型混合映射", () => {
    const mapping = decoder.buildMaskMapping(
      "手机[PHONE_1]，身份证[ID_CARD_1]",
      [
        { type: "PHONE_NUMBER", originalText: "13800138000", start: 3, end: 14 },
        { type: "ID_CARD", originalText: "510104198303123639", start: 18, end: 36 },
      ],
    );
    expect(mapping["[PHONE_1]"]).toBe("13800138000");
    expect(mapping["[ID_CARD_1]"]).toBe("510104198303123639");
  });

  test("同一明文多次出现时复用同一占位符", () => {
    const mapping = decoder.buildMaskMapping(
      "名字[NAME_1]和[NAME_1]",
      [
        { type: "NAME", originalText: "张三", start: 3, end: 5 },
        { type: "NAME", originalText: "张三", start: 8, end: 10 },
      ],
    );
    expect(mapping["[NAME_1]"]).toBe("张三");
  });

  test("部分脱敏值（无类型名）兜底匹配", () => {
    const mapping = decoder.buildMaskMapping("电话[138****8000_1]", [
      { type: "PHONE_NUMBER", originalText: "13800138000", start: 3, end: 14 },
    ]);
    expect(mapping["[138****8000_1]"]).toBe("13800138000");
  });

  test("无实体或空文本返回空映射", () => {
    expect(decoder.buildMaskMapping("[PHONE_1]", [])).toEqual({});
    expect(decoder.buildMaskMapping("", [
      { type: "PHONE_NUMBER", originalText: "13800138000", start: 0, end: 11 },
    ])).toEqual({});
    expect(decoder.buildMaskMapping(null, null)).toEqual({});
  });
});
