package com.hdu.apisensitivities.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URL;

@Configuration
public class QdrantConfig {

    @Value("${QDRANT_URL:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${QDRANT_API_KEY:}")
    private String apiKey;

    @Bean
    public QdrantClient qdrantClient() throws Exception {
        URL url = new URL(qdrantUrl);

        // 解析出主机名和端口号
        String host = url.getHost();
        int port = url.getPort() == -1 ? 6334 : url.getPort(); // Grpc默认通常是6334，或者是6333

        // 构建受 TLS 保护的 gRPC 客户端连接到云端 Qdrant
        return new QdrantClient(
                QdrantGrpcClient.newBuilder(host, port, true)
                        .withApiKey(apiKey)
                        .build());
    }
}