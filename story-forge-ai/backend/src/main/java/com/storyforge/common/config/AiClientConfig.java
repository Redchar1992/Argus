package com.storyforge.common.config;

import com.storyforge.ai.AiServiceClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
public class AiClientConfig {

    @Bean
    AiServiceClient aiServiceClient(AiProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory);
        if (StringUtils.hasText(properties.internalApiKey())) {
            builder.defaultHeader("X-Internal-API-Key", properties.internalApiKey());
        }
        RestClient restClient = builder.build();
        return new AiServiceClient(restClient);
    }
}
