chrome.runtime.onInstalled.addListener(() => {
  console.log('脱敏安全助手已安装')

  chrome.storage.local.set({
    settings: {
      autoDesense: true,
      autoWarn: true,
      enabledRules: {
        phone: true,
        email: true,
        idcard: true,
        bankcard: false
      }
    },
    stats: {
      totalDetections: 0,
      todayWarnings: 0,
      lastDate: new Date().toDateString()
    }
  })
})
