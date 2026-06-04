/**
 * 浏览器插件端 — 轻量级正则检测规则
 * 从服务器 PatternRegistry 同步移植，6 种可穷举模式的敏感类型。
 * 插件端零依赖，直接在输入框文本上扫描，命中后弹窗警告。
 */
const Patterns = {

  // 1. 手机号（兼容空格/横杠：138-1234-5678、138 1234 5678）
  PHONE_NUMBER: /(?:^|[^\d])(\+86)?1[3-9]\d([\s-]?\d{4}){2}(?!\d)/g,

  // 2. 邮箱
  EMAIL: /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}(?:\.[A-Za-z]{2,})?/g,

  // 3. 银行卡号（13-19位，支持空格/横杠）
  BANK_CARD: /(?:^|[^\d])[1-9]\d{3}(?:[\s-]?\d{4}){2,3}(?!\d)/g,

  // 4. 身份证号（18位，前6区域+8日期+3顺序+1校验）
  ID_CARD: /(?:^|[^\d])[1-9]\d{5}\s*(?:18|19|20)\d{2}\s*(?:0[1-9]|1[0-2])\s*(?:0[1-9]|[12]\d|3[01])\s*\d{3}[0-9Xx](?!\d)/g,

  // 5. 护照号（E/G开头+8位数字）
  PASSPORT: /(?:^|[^A-Z0-9])[EeGg]\d{8}(?!\d)/g,

  // 6. 车牌号（省级汉字+字母+5-6位数字字母）
  LICENSE_PLATE: /(?:^|[^A-Z0-9])[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙闽藏青川宁琼使领][A-Z][A-Z0-9]{5,6}(?![A-Z0-9])/g

};

/**
 * 扫描文本，返回命中的敏感信息列表。
 * @param {string} text - 输入框文本
 * @returns {Array<{type: string, text: string, index: number}>}
 */
function scanText(text) {
  const results = [];
  const labelMap = {
    'PHONE_NUMBER': '手机号',
    'EMAIL': '邮箱',
    'BANK_CARD': '银行卡号',
    'ID_CARD': '身份证号',
    'PASSPORT': '护照号',
    'LICENSE_PLATE': '车牌号'
  };

  for (const [type, regex] of Object.entries(Patterns)) {
    // 重置 lastIndex（全局正则必须）
    regex.lastIndex = 0;
    let match;
    while ((match = regex.exec(text)) !== null) {
      // 去除前导非数字字符（如：BANK_CARD 前面的 (?:^|[^\d])）
      const rawText = match[0];
      const cleanedText = rawText.replace(/^[^\dA-Za-z\u4e00-\u9fa5]+/, '');

      results.push({
        type: type,
        label: labelMap[type] || type,
        text: cleanedText,
        index: match.index + (rawText.length - cleanedText.length)
      });
    }
  }
  return results;
}

// 导出（支持 ES module 和 Chrome 插件全局变量两种方式）
if (typeof module !== 'undefined' && module.exports) {
  module.exports = { Patterns, scanText };
}
