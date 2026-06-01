function downloadBlob(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

export function buildMarkdownReport(results, llmText, detectedCount) {
  const date = new Date().toLocaleString();
  return `# 脱敏处理报告
生成时间: ${date}
LLM提供商: ${results.llmProvider}

## 1. 原始提示词
${results.originalPrompt}

## 2. 脱敏后提示词
${results.desensitizedPrompt}

## 3. LLM 响应
${llmText}

## 4. 敏感信息检测统计
共拦截: ${detectedCount} 个敏感实体
`;
}

export function exportMarkdownReport(content, fileName) {
  const blob = new Blob([content], { type: "text/markdown" });
  downloadBlob(blob, fileName);
}

async function loadCanvasTool() {
  const module = await import("html2canvas");
  return module.default;
}

async function loadPdfTool() {
  const module = await import("jspdf");
  return module.default;
}

export async function exportElementToPdf(element, fileName) {
  const [html2canvas, JsPdf] = await Promise.all([
    loadCanvasTool(),
    loadPdfTool(),
  ]);
  const canvas = await html2canvas(element, { scale: 2 });
  const imgData = canvas.toDataURL("image/png");
  const pdf = new JsPdf("p", "mm", "a4");
  const pdfWidth = pdf.internal.pageSize.getWidth();
  const pdfHeight = (canvas.height * pdfWidth) / canvas.width;

  pdf.addImage(imgData, "PNG", 0, 0, pdfWidth, pdfHeight);
  pdf.save(fileName);
}

export async function copyElementScreenshotOrDownload(element, fileName) {
  const html2canvas = await loadCanvasTool();
  const canvas = await html2canvas(element, { scale: 2 });

  return new Promise((resolve, reject) => {
    canvas.toBlob(async (blob) => {
      if (!blob) {
        reject(new Error("无法生成截图"));
        return;
      }

      try {
        const item = new ClipboardItem({ "image/png": blob });
        await navigator.clipboard.write([item]);
        resolve("copied");
      } catch {
        downloadBlob(blob, fileName);
        resolve("downloaded");
      }
    });
  });
}
