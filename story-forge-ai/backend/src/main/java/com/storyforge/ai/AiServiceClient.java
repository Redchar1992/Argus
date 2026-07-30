package com.storyforge.ai;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class AiServiceClient {

    private final RestClient restClient;

    public AiServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public JsonNode generateTopics(AiTopicRequest request) {
        try {
            JsonNode response = restClient.post()
                    .uri("/ai/topic/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new AiServiceException("AI 服务返回了空响应");
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw new AiServiceException(
                    "AI 服务返回 HTTP " + exception.getStatusCode().value(),
                    exception
            );
        } catch (RestClientException exception) {
            throw new AiServiceException("无法连接 AI 服务或请求超时", exception);
        }
    }
}
