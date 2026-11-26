package com.example.client.config;

import java.time.Duration;

import org.springframework.ai.model.ollama.autoconfigure.OllamaConnectionDetails;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import reactor.netty.http.client.HttpClient;

/**
 * Ollama API 클라이언트. 자동 구성 빈을 대체한다
 *
 * 바꾸는 것은 read timeout 이며, 프로퍼티로 이 값을 줄 방법이 Spring AI 1.1.8 기준으로 없어 코드로 직접 수정한다.
 */
@Configuration
public class EgovOllamaConfig {

    @Bean
    public OllamaApi ollamaApi(OllamaConnectionDetails connectionDetails) {
        // Sampling 핸들러에서 LLM 추론(문서 요약 등) 시 기본 read timeout 초과를 방지.
        // 기본값은 10초다.
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMinutes(5));

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(new ReactorClientHttpRequestFactory(httpClient));

        return new OllamaApi.Builder()
                .baseUrl(connectionDetails.getBaseUrl())
                .restClientBuilder(restClientBuilder)
                .build();
    }
}
