package com.example.client.config;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.client.dto.ServerStatusDto;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연결 실패 사유가 화면에 쓸 만한 형태로 기록되는지 검증한다.
 *
 * 두 축이 있다. 첫째, '인증' 과 그 밖을 구분하는가 - 고칠 곳이 다르기 때문이다.
 * 둘째, 사유 문구가 구체적인가 - SDK 가 덮어쓴 일반 메시지나 블로킹 타임아웃이 아니라
 * 실제 원인이 남아야 한다.
 *
 * 구분이 필요한 이유는 고칠 곳이 다르기 때문이다 - 인증이면 환경변수 MCP_API_KEY 를,
 * 그 밖이면 서버를 띄우고 [연결] 을 눌러야 한다. 화면 문구가 하나면 사용자를 반대편으로 보낸다.
 *
 * SDK 의 initialize() 가 원인을 일반 RuntimeException 으로 덮기 때문에 예외 메시지만으로는
 * 두 경우가 같은 문장이 된다. 그래서 cause 체인에서 서버가 보낸 JSON-RPC 오류 코드를 찾아
 * 판정한다. 그 경로 전체를 실제 SDK 로 통과시켜 확인한다.
 */
class EgovMcpServerRegistryFailureReasonTest {

    private static final String SERVER_NAME = "rag-server";
    private static final int ERROR_CODE_UNAUTHORIZED = -32001;

    private static final String SERVER_REASON =
            "요청에 X-MCP-API-Key 헤더가 없습니다. 클라이언트의 MCP 서버 연결 설정에 이 헤더를 추가하세요. "
            + "서버 설정은 정상입니다. 키 값은 대화에 노출하지 마세요.";

    @Test
    @DisplayName("서버가 403 + JSON-RPC 오류를 주면 인증 실패로 판정하고 서버 문구를 그대로 쓴다")
    void jsonRpcErrorIsClassifiedAsAuthFailure() throws Exception {
        byte[] body = new ObjectMapper().writeValueAsBytes(
                new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, null, null,
                        new McpSchema.JSONRPCResponse.JSONRPCError(
                                ERROR_CODE_UNAUTHORIZED, SERVER_REASON, null)));

        DisposableServer server = stubServer(403, body, "application/json");
        try {
            ServerStatusDto dto = registryFor(server.port()).connect(SERVER_NAME);

            assertThat(dto.status()).isEqualTo("FAILED");
            assertThat(dto.authFailed()).isTrue();
            assertThat(dto.lastError()).isEqualTo(SERVER_REASON);
        } finally {
            server.disposeNow();
        }
    }

    @Test
    @DisplayName("서버가 평문으로 거부하면 인증 실패로 판정할 수 없다 - 본문이 JSON-RPC 여야 하는 이유")
    void plainTextRejectionCannotBeClassified() {
        DisposableServer server = stubServer(
                403, SERVER_REASON.getBytes(StandardCharsets.UTF_8), "text/plain;charset=UTF-8");
        try {
            ServerStatusDto dto = registryFor(server.port()).connect(SERVER_NAME);

            assertThat(dto.status()).isEqualTo("FAILED");
            assertThat(dto.authFailed()).isFalse();
            assertThat(dto.lastError()).contains("403");
        } finally {
            server.disposeNow();
        }
    }

    @Test
    @DisplayName("서버가 꺼져 있으면 인증 실패가 아니라 연결 실패로 남는다")
    void connectionRefusedIsNotAuthFailure() {
        ServerStatusDto dto = registryFor(1).connect(SERVER_NAME);   // 포트 1 - 아무도 듣지 않는다

        assertThat(dto.status()).isEqualTo("FAILED");
        assertThat(dto.authFailed()).isFalse();
        // 일반 RuntimeException 메시지가 아니라 체인 최하위의 구체적 사유가 남아야 한다
        assertThat(dto.lastError())
                .contains("Connection refused")
                .doesNotContain("Client failed to initialize");
    }

    @Test
    @DisplayName("stdio stderr 가 있으면 그것을 앞세우고 예외 메시지는 괄호에 남긴다")
    void stdErrIsPreferredOverExceptionMessage() {
        EgovMcpServerRegistry registry = registryFor(1);
        var runtime = new EgovMcpServerRegistry.ServerRuntime();
        runtime.lastStdErr = "Error: None of the specified directories are accessible";

        registry.recordFailure(runtime,
                new IllegalStateException("Timeout on blocking read for 15000000000 NANOSECONDS"), null);

        assertThat(runtime.authFailed).isFalse();
        assertThat(runtime.lastError)
                .startsWith("Error: None of the specified directories are accessible")
                .contains("Timeout on blocking read");
    }

    @Test
    @DisplayName("stderr 는 한 번 쓰면 비워져 다음 실패에 재사용되지 않는다")
    void stdErrIsConsumedOnce() {
        EgovMcpServerRegistry registry = registryFor(1);
        var runtime = new EgovMcpServerRegistry.ServerRuntime();
        runtime.lastStdErr = "Error: 첫 번째 사유";

        registry.recordFailure(runtime, new IllegalStateException("처음"), null);
        registry.recordFailure(runtime, new IllegalStateException("나중"), null);

        assertThat(runtime.lastError).isEqualTo("나중");
    }

    @Test
    @DisplayName("stderr 가 없으면 체인 최하위 메시지만 쓰고 prefix 를 붙인다")
    void withoutStdErrUsesDeepestMessage() {
        EgovMcpServerRegistry registry = registryFor(1);
        var runtime = new EgovMcpServerRegistry.ServerRuntime();

        registry.recordFailure(runtime,
                new RuntimeException("겉면", new IllegalStateException("속 사유")), "서버 응답 없음: ");

        assertThat(runtime.lastError).isEqualTo("서버 응답 없음: 속 사유");
    }

    // helpers

    private DisposableServer stubServer(int status, byte[] body, String contentType) {
        return HttpServer.create().port(0)
                .handle((request, response) -> response.status(status)
                        .header("Content-Type", contentType)
                        .sendByteArray(Mono.just(body)))
                .bindNow();
    }

    private EgovMcpServerRegistry registryFor(int port) {
        McpStreamableHttpClientProperties props = new McpStreamableHttpClientProperties();
        props.getConnections().put(SERVER_NAME,
                new McpStreamableHttpClientProperties.ConnectionParameters(
                        "http://localhost:" + port, "/mcp"));

        return new EgovMcpServerRegistry(
                props, null, webClientBuilderProvider(), new ObjectMapper(),
                null, 3_000L, "test-client", "any-key");
    }

    private ObjectProvider<WebClient.Builder> webClientBuilderProvider() {
        return new ObjectProvider<>() {
            @Override
            public WebClient.Builder getObject() {
                return WebClient.builder();
            }

            @Override
            public WebClient.Builder getObject(Object... args) {
                return WebClient.builder();
            }
        };
    }
}
