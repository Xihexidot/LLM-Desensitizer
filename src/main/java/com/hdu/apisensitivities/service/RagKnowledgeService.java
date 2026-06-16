package com.hdu.apisensitivities.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagKnowledgeService {

    @Autowired
    private QdrantClient qdrantClient;

    @Value("${QDRANT_COLLECTION}")
    private String collectionName;

    /**
     * 🧠 核心方法：根据输入文本的向量，去云端 Qdrant 检索最相关的合规条文
     */
    public String retrieveRelevantRules(List<Float> queryVector) {
        if (queryVector == null || queryVector.isEmpty()) {
            return "";
        }
        try {
            log.info("正在检索云端 Qdrant 向量数据库，集合: {}", collectionName);

            // 1. 组装 gRPC 查询请求，检索最相似的 Top 2 条规则
            SearchPoints searchPoints = SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(queryVector) // 传入由大模型转好的向量
                    .setLimit(2)
                    .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true)) // 开启读取附加数据（即法条文本）
                    .build();

            // 2. 异步向云端 Qdrant 发起检索并等待结果
            List<ScoredPoint> searchResult = qdrantClient.searchAsync(searchPoints).get();

            // 3. 从 Payload 中提取出具体的法律合规文本
            String retrievedRules = searchResult.stream()
                    .map(point -> {
                        // 假设你在 Python 端存入 Qdrant 时，Payload 里的文本字段叫 "text" 或 "content"
                        var payloadMap = point.getPayloadMap();
                        if (payloadMap.containsKey("text")) {
                            return payloadMap.get("text").getStringValue();
                        } else if (payloadMap.containsKey("content")) {
                            return payloadMap.get("content").getStringValue();
                        }
                        return "";
                    })
                    .filter(text -> !text.isEmpty())
                    .collect(Collectors.joining("\n"));

            log.info("Qdrant 检索完成，成功匹配合规条文。");
            return retrievedRules;

        } catch (Exception e) {
            log.error("❌ Qdrant 检索合规知识失败", e);
            return ""; // 降级处理：检索失败时不崩业务，返回空字符串
        }
    }

    /**
     * 🛠️ 模拟方法：将打码后的文本转为向量
     * 提示：真实环境下你需要调用阿里云 DashScope 的 Embedding 接口，这里为了让你能先跑通流程，先用伪向量占位
     */
    public List<Float> getEmbedding(String text) {
        // TODO: 下半学期对接阿里云 text-embedding-v3 接口
        // 目前先返回一个固定维度的虚拟向量（比如 384 维或 1536 维）防止空指针
        List<Float> mockVector = new ArrayList<>();
        for (int i = 0; i < 384; i++) { // 与你 .env 中的 QDRANT_VECTOR_SIZE 对应
            mockVector.add(0.01f * (i % 10));
        }
        return mockVector;
    }
}