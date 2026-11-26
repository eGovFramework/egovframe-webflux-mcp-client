package com.example.client.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.spring.ClientMcpAsyncHandlersRegistry;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.scheduler.Schedulers;

/**
 * EgovMcpServerRegistry 를 만들어 자동 구성의 MCP 클라이언트를 대신한다. (자동 구성 둘을 제외)
 * 
 *  - org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration
 *  - org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration
 *
 * application.yml 의 spring.autoconfigure.exclude 에 명시되어 있다.
 * 자동 구성 전체를 버린 것이 아니라 이 둘만 빼고 나머지(전송 계층·프로퍼티·어노테이션 스캔)는 그대로 쓴다.
 *
 *   McpClientAutoConfiguration
 *     문제  @Bean 메서드 안에서 client.initialize().block(): 컨텍스트 초기화 중 블로킹.
 *           서버가 꺼져 있으면 기동 자체가 실패한다(Spring AI 1.1.8 에서 확인).
 *     대체  이 클래스의 mcpServerRegistry() + EgovMcpServerRegistry.connect()
 *
 *   McpToolCallbackAutoConfiguration
 *     문제  AsyncMcpToolCallbackProvider 를 싱글톤으로 한 번만 만든다. 생성 시점의
 *           클라이언트 목록으로 고정되므로 나중에 재연결해도 도구에 반영되지 않는다.
 *     대체  EgovMcpServerRegistry.getToolCallbacks(): 호출마다 다시 빌드한다.
 *
 * 연결 로직은 제외한 자동 구성에서 옮겨온 것이다. 무엇을 하는지는
 * 같고 '언제 · 몇 번 · 실패하면 어떻게' 만 바꿨다.
 *
 * 참고 : initialized: false 설정으로 해결되지 않는다.
 *
 * `spring.ai.mcp.client.initialized: false`(기본 `true`)로 위 블록 호출을
 * 끌 수는 있다. 그러면 기동은 성공하지만 다시 붙는 수단이 없어서 의미가 없다. 
 * 도구를 호출하면 세션이 없어 실패. 자동 구성의 클라이언트 목록은 싱글톤이라 기동 후 교체가 불가능하므로,
 * 서버가 늦게 올라오거나 중간에 끊기면 프로세스를 재시작하는 수밖에 없다.
 * 즉 '기동 실패' 만 막고 '동적 재연결' 은 그대로 남는다.
 */
@Slf4j
@Configuration
public class EgovMcpOptionalConfig {

    @Value("${spring.ai.mcp.client.request-timeout:60000}")
    private long requestTimeoutMs;

    @Value("${app.mcp-client-id:egovframe-webflux-mcp-client}")
    private String mcpClientId;

    @Value("${app.mcp-api-key:}")
    private String mcpApiKey;

    /**
     * 종료 정리는 `destroyMethod` 로 Spring 에 맡긴다.
     */
    @Bean(destroyMethod = "shutdown")
    public EgovMcpServerRegistry mcpServerRegistry(
            McpStreamableHttpClientProperties httpProperties,
            McpStdioClientProperties stdioProperties,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<ClientMcpAsyncHandlersRegistry> registryProvider) {

        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        ClientMcpAsyncHandlersRegistry handlerRegistry = registryProvider.getIfAvailable();

        EgovMcpServerRegistry registry = new EgovMcpServerRegistry(
                httpProperties,
                stdioProperties,
                webClientBuilderProvider,
                objectMapper,
                handlerRegistry,
                requestTimeoutMs,
                mcpClientId,
                mcpApiKey);

        // 전체 연결 시도를 기동 경로에서 떼어낸다.
        // tryConnectAll() 은 서버를 하나씩 돌며 initialize() 의 블로킹 읽기를 기다린다.
        // 이 메서드 안에서 그대로 부르면 컨텍스트 refresh 중의 main 스레드가 그 시간을 전부 사용
        // 응답 없는 서버 하나당 늘어난다.
        //응답 없는 stdio 서버를 등록한 경우, 기동 직후 사이드바가 DISCONNECTED 로 보일 수 있다.
        Schedulers.boundedElastic().schedule(registry::tryConnectAll);

        return registry;
    }
}
