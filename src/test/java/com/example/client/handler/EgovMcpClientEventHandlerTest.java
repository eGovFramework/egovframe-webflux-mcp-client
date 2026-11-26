package com.example.client.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.annotation.spring.AsyncMcpAnnotationProviders;

import com.example.client.support.EgovIngestProgressRegistry;
import com.example.client.support.EgovIngestProgressRegistry.ProgressEvent;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * EgovMcpClientEventHandler 가 MCP 클라이언트 핸들러로 실제로 등록되는지 검증한다.
 *
 * 왜 이 테스트가 필요한가
 *
 * 핸들러를 void 로 선언하면 등록되지 않는다. 컴파일 오류도 예외도 나지 않고
 * 기동 로그에 WARN 한 줄만 남고 등록에서 빠진다. McpPredicates.isReactiveReturnType
 * 가 Mono·Flux·Publisher 만 참이기 때문이다(McpPredicates:51).
 *
 * 실제로 이 클래스의 핸들러 5개가 전부 void 라 동작한 적이 없었고, 서버가 알림을
 * 안 보내는 것으로 오인됐다. 증상이 "아무 일도 일어나지 않음" 이라 기능 테스트로는
 * 잡히지 않는다.
 *
 * 그래서 내 이해를 검증하지 않고 런타임이 쓰는 바로 그 프로바이더를 호출한다
 * (ClientMcpAsyncHandlersRegistry.afterSingletonsInstantiated 와 같은 경로).
 * 반환 타입을 void 로 되돌리면 이 테스트가 깨진다.
 */
class EgovMcpClientEventHandlerTest {

    private EgovIngestProgressRegistry progressRegistry;
    private EgovMcpClientEventHandler handler;

    private static final String CLIENT = "rag-server";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @BeforeEach
    void setUp() {
        progressRegistry = new EgovIngestProgressRegistry();
        handler = new EgovMcpClientEventHandler(mock(ChatModel.class), progressRegistry);
    }

    // 등록: 반환 타입이 Mono 가 아니면 여기서 걸린다

    @Test
    @DisplayName("@McpProgress 핸들러가 rag-server 용으로 등록된다")
    void registersProgressHandler() {
        var specs = AsyncMcpAnnotationProviders.progressSpecifications(List.of(handler));

        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).clients()).containsExactly(CLIENT);
    }

    @Test
    @DisplayName("@McpSampling 핸들러가 rag-server 용으로 등록된다")
    void registersSamplingHandler() {
        var specs = AsyncMcpAnnotationProviders.samplingSpecifications(List.of(handler));

        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).clients()).containsExactly(CLIENT);
    }

    // 동작: 등록된 핸들러를 프로바이더가 만든 함수로 호출한다

    @Test
    @DisplayName("등록된 진행률 핸들러를 호출하면 jobId 싱크로 흘러간다")
    void registeredProgressHandlerFeedsRegistry() {
        var spec = AsyncMcpAnnotationProviders.progressSpecifications(List.of(handler)).get(0);
        String jobId = "job-handler";
        progressRegistry.register(jobId);

        // 런타임과 동일하게, 반환된 Mono 를 구독해야 핸들러 본문이 실행된다.
        spec.progressHandler()
                .apply(new McpSchema.ProgressNotification(jobId, 2d, 4d, "요약 완료", null))
                .block(TIMEOUT);

        progressRegistry.complete(jobId);

        List<ProgressEvent> events = progressRegistry.subscribe(jobId).collectList().block(TIMEOUT);
        assertThat(events).extracting(ProgressEvent::message).containsExactly("요약 완료");
    }

    @Test
    @DisplayName("구독자 없는 토큰이 와도 핸들러가 실패하지 않는다")
    void unknownTokenDoesNotFailHandler() {
        var spec = AsyncMcpAnnotationProviders.progressSpecifications(List.of(handler)).get(0);

        // 범용 클라이언트·채팅 경로의 적재가 이렇게 도착한다. 오류가 아니다.
        spec.progressHandler()
                .apply(new McpSchema.ProgressNotification("남의-작업", 1d, 4d, "텍스트 추출 완료", null))
                .block(TIMEOUT);

        assertThat(progressRegistry.size()).isZero();
    }

    // 샘플링 응답의 model 필드: 하드코딩하면 거짓 보고가 된다

    @Test
    @DisplayName("샘플링 응답의 model 은 실제 응답 모델 이름이다")
    void samplingReportsActualModelName() {
        ChatModel chatModel = modelReturning(
                ChatResponseMetadata.builder().model("qwen3-4b:Q4_K_M").build());
        var subject = new EgovMcpClientEventHandler(chatModel, progressRegistry);

        McpSchema.CreateMessageResult result = subject.handleSampling(samplingRequest()).block(TIMEOUT);

        assertThat(result).isNotNull();
        // 전에는 "ollama" 가 하드코딩돼 있어, 백엔드를 바꾼 PC 에서도 그렇게 보고했다.
        assertThat(result.model()).isEqualTo("qwen3-4b:Q4_K_M");
    }

    @Test
    @DisplayName("응답에 모델 이름이 없으면 unknown - 추측해서 채우지 않는다")
    void samplingReportsUnknownWhenModelNameMissing() {
        ChatModel chatModel = modelReturning(ChatResponseMetadata.builder().build());
        var subject = new EgovMcpClientEventHandler(chatModel, progressRegistry);

        McpSchema.CreateMessageResult result = subject.handleSampling(samplingRequest()).block(TIMEOUT);

        assertThat(result).isNotNull();
        assertThat(result.model()).isEqualTo("unknown");
    }

    private ChatModel modelReturning(ChatResponseMetadata metadata) {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("요약 결과"))), metadata);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        return chatModel;
    }

    private McpSchema.CreateMessageRequest samplingRequest() {
        return McpSchema.CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(
                        McpSchema.Role.USER, new McpSchema.TextContent("이 문서를 요약해줘"))))
                .maxTokens(256)
                .build();
    }
}
