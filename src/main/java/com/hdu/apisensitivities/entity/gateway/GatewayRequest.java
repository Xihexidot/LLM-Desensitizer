package com.hdu.apisensitivities.entity.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayRequest {
    private ProviderPreference provider;
    private RequestContext requestContext;
    private UserContext userContext;
    private InputPayload input;
    private GatewayOptions options;
    private Map<String, Object> metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderPreference {
        private String preferred;
        private List<String> fallback;
        private Boolean allowExternal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestContext {
        private String sessionId;
        private String sceneCode;
        private String channel;
        private String environment;
        private String dataClassification;
        private Boolean isExternalModel;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserContext {
        private String userId;
        private String userRole;
        private String department;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InputPayload {
        private String type;
        private String content;
        private Map<String, Object> structuredData;
        private List<Attachment> attachments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Attachment {
        private String fileName;
        private String mediaType;
        private String storageKey;
        private Long size;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GatewayOptions {
        private Boolean stream;
        private Boolean enableOutputReview;
        private Boolean enableDesensitization;
        private Boolean strictMode;
    }
}
