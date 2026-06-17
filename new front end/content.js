const settings = {
  autoDesense: true,
  autoWarn: true,
  enabledRules: {
    phone: true,
    email: true,
    idcard: true,
    bankcard: false
  }
}

const dangerKeywords = [
  { word: '炸药', level: 'danger', message: '⚠️ 检测到危险词汇"炸药"' },
  { word: '炸弹', level: 'danger', message: '⚠️ 检测到危险词汇"炸弹"' },
  { word: '恐怖袭击', level: 'danger', message: '⚠️ 检测到敏感内容"恐怖袭击"' },
  { word: '杀人', level: 'danger', message: '⚠️ 检测到暴力词汇"杀人"' },
  { word: '毒品', level: 'danger', message: '⚠️ 检测到毒品相关词汇"毒品"' },
  { word: '枪支', level: 'danger', message: '⚠️ 检测到武器相关词汇"枪支"' },
  { word: '爆炸', level: 'warning', message: '⚠️ 检测到危险行为词汇"爆炸"' },
  { word: '暴力', level: 'warning', message: '⚠️ 检测到暴力相关词汇"暴力"' }
]

function desensitize(text) {
  if (!text || !settings.autoDesense) return text;
  let result = text;

  // 1. 身份证：优先处理，且必须加上边界判断（不是数字开头/结尾的部分）
  if (settings.enabledRules.idcard) {
    // 使用 \b 边界匹配，避免匹配到银行卡或手机号的一部分
    result = result.replace(/\b\d{17}[\dXx]|\b\d{15}\b/g, (match) => {
      // 只保留前6位，后面全部用*
      return match.slice(0, 6) + '*'.repeat(match.length - 6);
    });
  }

  // 2. 手机号：保留前3位和后4位，中间****
  if (settings.enabledRules.phone) {
    result = result.replace(/\b1[3-9]\d{9}\b/g, (match) => {
      return match.slice(0, 3) + '****' + match.slice(-4);
    });
  }

  // 3. 邮箱：必须包含@和完整域名
  if (settings.enabledRules.email) {
    result = result.replace(/([A-Za-z0-9._%+-]+)@([A-Za-z0-9.-]+\.[A-Za-z]{2,})/g, (match, username, domain) => {
      let hidden;
      if (username.length <= 2) {
        hidden = '*'.repeat(username.length);
      } else {
        hidden = username[0] + '*'.repeat(username.length - 2) + username[username.length - 1];
      }
      return hidden + '@' + domain;
    });
  }

  // 4. 银行卡号：保留前4位和后4位
  if (settings.enabledRules.bankcard) {
    result = result.replace(/\b\d{16,19}\b/g, (match) => {
      return match.slice(0, 4) + '*'.repeat(match.length - 8) + match.slice(-4);
    });
  }

  return result;
}
// 检测危险词
function detectDanger(text) {
  if (!text || !settings.autoWarn) return []
  const found = []
  for (const kw of dangerKeywords) {
    if (text.toLowerCase().includes(kw.word.toLowerCase())) {
      found.push(kw)
    }
  }
  return found
}

// 显示通知
function showNotification(message, type = 'info') {
  const colors = {
    danger: '#dc2626',
    warning: '#f59e0b',
    info: '#3b82f6'
  }
  
  const notification = document.createElement('div')
  notification.textContent = message
  notification.style.cssText = `
    position: fixed;
    bottom: 80px;
    right: 20px;
    max-width: 320px;
    padding: 12px 16px;
    background: ${colors[type] || colors.info};
    color: white;
    border-radius: 12px;
    font-size: 13px;
    z-index: 999999;
    box-shadow: 0 4px 15px rgba(0,0,0,0.2);
    font-family: system-ui, -apple-system, sans-serif;
    animation: slideInRight 0.3s ease;
  `
  
  if (!document.querySelector('#desense-style')) {
    const style = document.createElement('style')
    style.id = 'desense-style'
    style.textContent = `
      @keyframes slideInRight {
        from { transform: translateX(100px); opacity: 0; }
        to { transform: translateX(0); opacity: 1; }
      }
    `
    document.head.appendChild(style)
  }
  
  document.body.appendChild(notification)
  setTimeout(() => notification.remove(), 3000)
}

let floatingBtn = null

function createFloatingButton() {
  if (floatingBtn || !document.body) return
  
  floatingBtn = document.createElement('div')
  floatingBtn.id = 'desense-floating-btn'
  floatingBtn.innerHTML = '🛡️'
  floatingBtn.title = '脱敏安全助手 - 点击查看状态'
  floatingBtn.style.cssText = `
    position: fixed;
    bottom: 20px;
    right: 20px;
    width: 48px;
    height: 48px;
    background: linear-gradient(135deg, #667eea, #764ba2);
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 24px;
    cursor: pointer;
    z-index: 99999;
    box-shadow: 0 4px 15px rgba(0,0,0,0.25);
    transition: transform 0.2s;
    font-family: sans-serif;
  `
  
  floatingBtn.onmouseenter = () => floatingBtn.style.transform = 'scale(1.05)'
  floatingBtn.onmouseleave = () => floatingBtn.style.transform = 'scale(1)'
  floatingBtn.onclick = (e) => {
    e.stopPropagation()
    alert('🛡️ 脱敏安全助手\n\n✅ 正在运行中！\n\n功能：\n• 手机号 → 138****5678（中间4位隐藏）\n• 邮箱 → a***b@example.com（首尾保留，中间星号）\n• 身份证 → 110101************（前6位保留）\n• 检测危险词并警告\n\n在输入框中输入敏感信息即可测试')
  }
  
  document.body.appendChild(floatingBtn)
  console.log('[脱敏助手] ✅ 已启动，正在监听输入框...')
}

const monitoredInputs = new WeakSet()

function getInputValue(element) {
  if (!element) return '';
  if (element.value !== undefined) return element.value;
  if (element.innerText) return element.innerText;
  return element.textContent || '';
}

function setInputValue(element, value) {
  if (!element) return;
  if (element.value !== undefined) {
    element.value = value;
  } else {
    element.innerText = value;
  }
  element.dispatchEvent(new Event('input', { bubbles: true }));
}

// 防抖函数，避免频繁触发
function debounce(func, delay) {
  let timer;
  return function(...args) {
    clearTimeout(timer);
    timer = setTimeout(() => func.apply(this, args), delay);
  };
}

function monitorInput(input) {
  if (monitoredInputs.has(input)) return
  monitoredInputs.add(input)
  
  console.log('[脱敏助手] 监听输入框:', input.tagName, input.id || input.className || '')
  
  // 使用防抖，避免输入过程中频繁弹窗
  const handleInput = debounce((e) => {
    const inputEl = e.target;
    const originalText = getInputValue(inputEl);
    if (!originalText) return;

    // 危险词检测
    const dangers = detectDanger(originalText);
    if (dangers.length > 0) {
      dangers.forEach(d => showNotification(d.message, d.level));
    }

    // 脱敏处理
    let desensitizedText = originalText;
    if (settings.autoDesense) {
      desensitizedText = desensitize(originalText);
    }

    // 如果有变化，弹出确认框（只弹一次）
    if (desensitizedText !== originalText) {
      // 避免重复弹窗，检查是否已经弹过
      if (inputEl._confirming) return;
      inputEl._confirming = true;
      
      const userConfirmed = confirm("检测到敏感信息，是否脱敏？\n\n原文本：" + originalText + "\n\n脱敏后：" + desensitizedText);
      if (userConfirmed) {
        setInputValue(inputEl, desensitizedText);
        showNotification('✅ 脱敏完成');
      }
      
      setTimeout(() => {
        inputEl._confirming = false;
      }, 500);
    }
  }, 300); // 300ms 防抖，等用户输入停顿后再处理
  
  input.addEventListener('input', handleInput)
  // 移除 keyup 监听，避免重复触发（input 已经足够）
}

// 查找所有输入框
function findAllInputs() {
  const selectors = [
    'textarea',
    'input[type="text"]',
    'input[type="search"]',
    'input:not([type])',
    '[contenteditable="true"]',
    '[contenteditable="plaintext-only"]',
    '[role="textbox"]',
    '[role="combobox"]',
    '[role="searchbox"]',
    '.chat-input',
    '.message-input',
    '.input-area',
    '.input-box',
    '.chat-input-area',
    '#chat-input',
    '#message-input',
    '#prompt-textarea',
    '#chat-textarea',
    '.ql-editor',
    '[data-slate-editor]',
    '.ProseMirror',
    '.CodeMirror textarea'
  ]
  
  const inputs = []
  for (const selector of selectors) {
    try {
      const elements = document.querySelectorAll(selector)
      if (elements.length) {
        inputs.push(...Array.from(elements))
      }
    } catch(e) {}
  }
  
  return [...new Set(inputs)]
}

let scanCount = 0
function scanAndMonitor() {
  const inputs = findAllInputs()
  inputs.forEach(input => monitorInput(input))
  
  scanCount++
  if (scanCount === 1 && inputs.length > 0) {
    console.log(`[脱敏助手] ✅ 已找到 ${inputs.length} 个输入框，开始监听！`)
  }
}

function init() {
  if (!document.body) {
    setTimeout(init, 100)
    return
  }
  
  createFloatingButton()
  scanAndMonitor()
  
  setInterval(scanAndMonitor, 2000)
  
  const observer = new MutationObserver(() => {
    scanAndMonitor()
  })
  observer.observe(document.body, { childList: true, subtree: true })
  
  console.log('[脱敏助手] 🛡️ 脱敏安全助手已启动！')
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init)
} else {
  init()
}
