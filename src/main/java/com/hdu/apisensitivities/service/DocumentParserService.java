package com.hdu.apisensitivities.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Slf4j
@Service
public class DocumentParserService {

    public String extractTextFromFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传的文件不能为空！");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null) return "";
        fileName = fileName.toLowerCase();

        try (InputStream inputStream = file.getInputStream()) {
            if (fileName.endsWith(".pdf")) {
                return extractTextFromPdf(file.getBytes());
            } else if (fileName.endsWith(".docx")) {
                return extractTextFromDocx(inputStream);
            } else if (fileName.endsWith(".txt")) {
                return new String(file.getBytes());
            } else {
                throw new UnsupportedOperationException("目前仅支持解析 .pdf, .docx 和 .txt 格式的文件");
            }
        } catch (Exception e) {
            log.error("文件解析失败: {}", e.getMessage());
            throw new RuntimeException("文件内容提取失败，请检查文件是否损坏。");
        }
    }

    private String extractTextFromPdf(byte[] fileBytes) throws Exception {
        log.info("正在使用 PDFBox 解析 PDF 文件...");
        try (PDDocument document = PDDocument.load(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String extractTextFromDocx(InputStream inputStream) throws Exception {
        log.info("正在使用 Apache POI 解析 Word 文件...");
        try (XWPFDocument doc = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }
}
