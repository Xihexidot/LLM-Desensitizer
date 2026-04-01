// package com.hdu.apisensitivities.service;

// import com.fasterxml.jackson.databind.ObjectMapper;
// import com.hdu.apisensitivities.entity.DesensitizationRequest;
// import com.hdu.apisensitivities.entity.DesensitizationResponse;
// import com.hdu.apisensitivities.entity.SensitiveEntity;
// import com.hdu.apisensitivities.manager.DesensitizationManager;
// import org.junit.jupiter.api.Test;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.boot.test.context.SpringBootTest;
// import org.springframework.core.io.ClassPathResource;

// import java.io.File;
// import java.util.*;

// import static org.junit.jupiter.api.Assertions.*;

// @SpringBootTest
// public class DesensitizationTest {

//     @Autowired
//     private DesensitizationManager desensitizationManager;

//     private final ObjectMapper objectMapper = new ObjectMapper();

//     @Test
//     void testChinesePII() throws Exception {
//         // 1. 读取测试数据文件
//         // 从 src/test/resources/ 目录读取
//         ClassPathResource resource = new ClassPathResource("pii_test_data.json");
//         File testDataFile = resource.getFile();
        
//         // 读取JSON数组
//         List<TestCase> testCases = objectMapper.readValue(testDataFile, 
//                 objectMapper.getTypeFactory().constructCollectionType(List.class, TestCase.class));
        
//         System.out.println("加载测试用例数量: " + testCases.size());
        
//         // 统计结果
//         List<TestResult> results = new ArrayList<>();
        
//         // 2. 循环每一条数据
//         for (TestCase testCase : testCases) {
//             DesensitizationRequest request = new DesensitizationRequest();
//             request.setMainContent(testCase.getContent());
//             request.setDataType("TEXT");
            
//             // 3. 执行脱敏
//             DesensitizationResponse response = desensitizationManager.process(request);
            
//             // 4. 对比识别结果
//             List<SensitiveEntity> detectedEntities = response.getEntities();
//             List<ExpectedEntity> expectedEntities = testCase.getExpectedEntities();
            
//             // 计算召回率
//             double recall = calculateRecall(detectedEntities, expectedEntities);
            
//             TestResult result = new TestResult();
//             result.setId(testCase.getId());
//             result.setContent(testCase.getContent());
//             result.setExpectedCount(expectedEntities.size());
//             result.setDetectedCount(detectedEntities.size());
//             result.setRecall(recall);
//             result.setDetectedEntities(detectedEntities);
//             result.setExpectedEntities(expectedEntities);
            
//             results.add(result);
            
//             // 打印详细信息（可选）
//             if (recall < 0.8) {
//                 System.out.println("低召回率用例 ID: " + testCase.getId() + ", 召回率: " + recall);
//             }
//         }
        
//         // 5. 输出整体统计
//         printStatistics(results);
//     }
    
//     /**
//      * 计算召回率
//      * 召回率 = 正确识别的实体数 / 期望实体总数
//      */
//     private double calculateRecall(List<SensitiveEntity> detected, List<ExpectedEntity> expected) {
//         if (expected.isEmpty()) {
//             return 1.0; // 如果没有期望的实体，认为是100%正确
//         }
        
//         int correctlyIdentified = 0;
        
//         for (ExpectedEntity expectedEntity : expected) {
//             // 检查是否有匹配的检测结果
//             boolean found = detected.stream().anyMatch(detectedEntity -> 
//                 isMatch(detectedEntity, expectedEntity)
//             );
            
//             if (found) {
//                 correctlyIdentified++;
//             }
//         }
        
//         return (double) correctlyIdentified / expected.size();
//     }
    
//     /**
//      * 判断检测到的实体是否与期望的实体匹配
//      * 匹配条件：类型相同且内容相同
//      */
//     private boolean isMatch(SensitiveEntity detected, ExpectedEntity expected) {
//         // 比较敏感类型
//         if (!detected.getSensitiveType().equals(expected.getType())) {
//             return false;
//         }
        
//         // 比较文本内容
//         String detectedText = detected.getText();
//         String expectedText = expected.getText();
        
//         // 可以忽略大小写，或者使用包含关系
//         return detectedText != null && expectedText != null && 
//                (detectedText.equals(expectedText) || 
//                 detectedText.contains(expectedText) || 
//                 expectedText.contains(detectedText));
//     }
    
//     /**
//      * 打印统计信息
//      */
//     private void printStatistics(List<TestResult> results) {
//         System.out.println("\n========== 测试统计 ==========");
//         System.out.println("总测试用例数: " + results.size());
        
//         double avgRecall = results.stream()
//                 .mapToDouble(TestResult::getRecall)
//                 .average()
//                 .orElse(0.0);
        
//         System.out.printf("平均召回率: %.2f%%\n", avgRecall * 100);
        
//         // 召回率分布
//         long highRecall = results.stream().filter(r -> r.getRecall() >= 0.9).count();
//         long mediumRecall = results.stream().filter(r -> r.getRecall() >= 0.7 && r.getRecall() < 0.9).count();
//         long lowRecall = results.stream().filter(r -> r.getRecall() < 0.7).count();
        
//         System.out.println("\n召回率分布:");
//         System.out.println("  优秀 (≥90%): " + highRecall + " 个用例");
//         System.out.println("  良好 (70%-90%): " + mediumRecall + " 个用例");
//         System.out.println("  待改进 (<70%): " + lowRecall + " 个用例");
        
//         // 打印召回率最低的5个用例
//         System.out.println("\n召回率最低的5个用例:");
//         results.stream()
//                 .sorted(Comparator.comparingDouble(TestResult::getRecall))
//                 .limit(5)
//                 .forEach(r -> System.out.printf("  ID: %s, 召回率: %.2f%%, 期望: %d, 检测到: %d\n",
//                         r.getId(), r.getRecall() * 100, r.getExpectedCount(), r.getDetectedCount()));
//     }
    
//     // ========== 内部类定义 ==========
    
//     /**
//      * 测试用例数据结构
//      */
//     static class TestCase {
//         private String id;
//         private String content;
//         private String language;
//         private List<ExpectedEntity> expectedEntities;
        
//         // getters and setters
//         public String getId() { return id; }
//         public void setId(String id) { this.id = id; }
        
//         public String getContent() { return content; }
//         public void setContent(String content) { this.content = content; }
        
//         public String getLanguage() { return language; }
//         public void setLanguage(String language) { this.language = language; }
        
//         public List<ExpectedEntity> getExpectedEntities() { return expectedEntities; }
//         public void setExpectedEntities(List<ExpectedEntity> expectedEntities) { 
//             this.expectedEntities = expectedEntities; 
//         }
//     }
    
//     /**
//      * 期望的实体数据结构
//      */
//     static class ExpectedEntity {
//         private String text;
//         private String type;
//         private Integer start;
//         private Integer end;
        
//         // getters and setters
//         public String getText() { return text; }
//         public void setText(String text) { this.text = text; }
        
//         public String getType() { return type; }
//         public void setType(String type) { this.type = type; }
        
//         public Integer getStart() { return start; }
//         public void setStart(Integer start) { this.start = start; }
        
//         public Integer getEnd() { return end; }
//         public void setEnd(Integer end) { this.end = end; }
//     }
    
//     /**
//      * 测试结果数据结构
//      */
//     static class TestResult {
//         private String id;
//         private String content;
//         private int expectedCount;
//         private int detectedCount;
//         private double recall;
//         private List<SensitiveEntity> detectedEntities;
//         private List<ExpectedEntity> expectedEntities;
        
//         // getters and setters
//         public String getId() { return id; }
//         public void setId(String id) { this.id = id; }
        
//         public String getContent() { return content; }
//         public void setContent(String content) { this.content = content; }
        
//         public int getExpectedCount() { return expectedCount; }
//         public void setExpectedCount(int expectedCount) { this.expectedCount = expectedCount; }
        
//         public int getDetectedCount() { return detectedCount; }
//         public void setDetectedCount(int detectedCount) { this.detectedCount = detectedCount; }
        
//         public double getRecall() { return recall; }
//         public void setRecall(double recall) { this.recall = recall; }
        
//         public List<SensitiveEntity> getDetectedEntities() { return detectedEntities; }
//         public void setDetectedEntities(List<SensitiveEntity> detectedEntities) { 
//             this.detectedEntities = detectedEntities; 
//         }
        
//         public List<ExpectedEntity> getExpectedEntities() { return expectedEntities; }
//         public void setExpectedEntities(List<ExpectedEntity> expectedEntities) { 
//             this.expectedEntities = expectedEntities; 
//         }
//     }
// }