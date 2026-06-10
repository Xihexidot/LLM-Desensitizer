chrome.storage.local.get(['stats'], (result) => {
  if (result.stats) {
    document.getElementById('totalDetections').textContent = result.stats.totalDetections || 0
    document.getElementById('todayWarnings').textContent = result.stats.todayWarnings || 0
  }
})

document.getElementById('openPanel').onclick = () => {
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    chrome.tabs.sendMessage(tabs[0].id, { action: 'openPanel' })
  })
}