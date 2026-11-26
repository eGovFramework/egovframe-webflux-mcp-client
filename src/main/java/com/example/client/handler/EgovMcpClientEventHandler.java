package com.example.client.handler;

import java.util.ArrayList;
import java.util.List;

import org.springaicommunity.mcp.annotation.McpProgress;
import org.springaicommunity.mcp.annotation.McpSampling;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.client.support.EgovIngestProgressRegistry;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP 클라이언트 이벤트 핸들러
 *
 * MCP 서버에서 전송하는 알림(Notification)과 요청(Request)을 처리.
 *
 * clients 값은 yml 의 연결 키다.
 *
 * 모든 애너테이션의 clients = "rag-server" 는 application.yml 의 연결 키와
 * 같아야 한다 (spring.ai.mcp.client.streamable-http.connections.rag-server).
 *
 * 이름을 넘기는 쪽은 Spring AI 가 아니라 이 앱이다. 자동 구성을 제외했으므로
 * EgovMcpServerRegistry.wireAndBuild 가 연결 키를 각 핸들러 람다에 실어
 * ClientMcpAsyncHandlersRegistry 로 넘기고, 거기서 clients 와 비교된다.
 *
 * 어긋나면 예외가 아니라 알림이 오지 않는 현상으로 나타난다. 기동 로그의
 * "Registering ... handler for rag-server" 줄로 등록 여부를 확인할 수 있다.
 *
 * 구현된 핸들러는 다음과 같다.
 *
 *  @McpSampling   서버의 LLM 샘플링 요청 → 주입된 ChatModel 로 위임 처리 (Deprecated 예고 상태)
 *  @McpProgress   서버 적재 진행률 알림 → 로그 + jobId 별 SSE 싱크
 *
 * 다만 McpSampling은 SEP-2577(2026-07-28 개정)에서 Sampling 을 Dprecated 예고했다. 
 * mcp-core 0.18.3기준으로는 는 프로토콜 2025-11-25 라
 * 아직 영향이 없는 것을 확인하였다.
 *
 * 반환 타입은 반드시 Mono 여야 한다.
 *
 * 이 클래스의 핸들러를 void 로 선언하면 등록되지 않는다. 
 * 컴파일 오류나 예외 없이, 기동 로그에 WARN만 출력된다.
 *
 * ASYNC Providers don't support imperative (non-reactive) return types.
 * Skipping method ... with non-reactive return type void
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovMcpClientEventHandler {

    private final ChatModel chatModel;
    private final EgovIngestProgressRegistry progressRegistry;
    
    /**
     * MCP 서버가 장시간 작업의 진행 단계를 알릴 때 호출된다. (@McpProgress 사용)
     *
     * 현재 보내는 쪽은 서버 ingestDocument 파이프라인 하나다.
     * (EgovDocumentIngestServiceImpl.runPipeline: 텍스트 추출 · 요약 · 청킹 · 임베딩 4단계).
     *
     * 요청에 progressToken 이 있어야 호출된다.
     *
     * 서버에 `ctx.progress(...)` 가 있어도 요청의 `_meta.progressToken` 이
     * 비어 있으면 알림은 전송되지 않는다. mcp-annotations `DefaultMcpAsyncRequestContext.progress()` 가
     * ProgressNotification 만들지 않고 `Mono.empty()` 로 끝내면서 WARN 을 남긴다.
     * 
     * "Progress notification not supported by the client!"
     * 문구를 그대로 해석하면 클라이언트에서 지원하지 않는다는 것이지만 
     * 실제로는 요청에 progressToken 이 없어도 위와 같은 로그가 나온다.
     *
     *
     * 토큰으로 작업을 분류한다. 
     *
     * 어느 호출의 진행률인지는 `progressToken` 으로만 알 수 있다.
     * 적재는 토큰을 `jobId` 와 같은 값으로 보내므로 레지스트리가 해당 SSE 스트림을 찾아낸다.
     *
     * 구독자가 없는 토큰은 레지스트리가 버린다. 범용 클라이언트(VS Code 등)가
     * 호출한 적재나 채팅 경로의 적재가 여기로 올 수 있으나 버려지게 되고 오류는 아니다.
     */
    @McpProgress(clients = "rag-server")
    public Mono<Void> handleProgress(McpSchema.ProgressNotification notification) {
        return Mono.fromRunnable(() -> {
            boolean delivered = progressRegistry.emit(notification);

            log.info("[MCP] 진행률 - {}/{} {} (token: {}, 구독자: {})",
                    notification.progress(),
                    notification.total(),
                    notification.message(),
                    safeToken(notification.progressToken()),
                    delivered ? "있음" : "없음");
        });
    }

    /**
     * 토큰을 로그에 싣기 전에 형식을 확인한다. 형식에서 벗어난 값은 찍지 않는다.
     */
    private String safeToken(Object progressToken) {
        if (progressToken == null) {
            return "(없음)";
        }
        String token = progressToken.toString();
        return EgovIngestProgressRegistry.isValidJobId(token) ? token : "(형식 외)";
    }

    /**
     * MCP 서버가 LLM 추론을 클라이언트에게 위임할 때 호출. (@McpSampling 사용)
     *
     * MCP Sampling 개념:
     *   서버가 직접 LLM을 갖지 않아도 클라이언트의 LLM을 활용하는 기능
     * 서버가 ctx.sampleEnabled() / ctx.sample() 을 호출하면 이 핸들러가 실행된다.
     */
    @McpSampling(clients = "rag-server")
    public Mono<McpSchema.CreateMessageResult> handleSampling(McpSchema.CreateMessageRequest request) {
        return Mono.fromCallable(() -> {
            log.info("[MCP] 샘플링 요청 수신 - 메시지 수: {}, maxTokens: {}",
                    request.messages().size(), request.maxTokens());

            List<Message> messages = buildSpringAiMessages(request);

            ChatResponse response = chatModel.call(new Prompt(messages));
            String responseText = response.getResult().getOutput().getText();

            // MCP 규격상 model 은 '이 메시지를 생성한 모델의 이름' 이다.
            String modelName = resolveModelName(response);

            log.debug("[MCP] 샘플링 응답 생성 완료 - 모델: {}, 길이: {}자", modelName, responseText.length());

            return McpSchema.CreateMessageResult.builder()
                    .role(McpSchema.Role.ASSISTANT)
                    .content(new McpSchema.TextContent(responseText))
                    .model(modelName)
                    .stopReason(McpSchema.CreateMessageResult.StopReason.END_TURN)
                    .build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // private helpers

    /**
     * 응답을 생성한 모델 이름. 메타데이터가 비어 있으면 "unknown" 이다.
     */
    private String resolveModelName(ChatResponse response) {
        String model = response.getMetadata() != null ? response.getMetadata().getModel() : null;
        return (model != null && !model.isBlank()) ? model : "unknown";
    }

    /**
     * MCP CreateMessageRequest를 Spring AI Prompt용 메시지 목록으로 변환.
     *
     * 변환 규칙:
     *   1. systemPrompt가 있으면 SystemMessage로 맨 앞에 추가
     *   2. SamplingMessage.role == USER      → UserMessage
     *   3. SamplingMessage.role == ASSISTANT → AssistantMessage
     *   4. Content가 TextContent가 아니면 "[비텍스트 콘텐츠]" 라는 고정 문구로 대신한다
     *
     */
    private List<Message> buildSpringAiMessages(McpSchema.CreateMessageRequest request) {
        List<Message> messages = new ArrayList<>();

        if (StringUtils.hasText(request.systemPrompt())) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }

        for (McpSchema.SamplingMessage samplingMsg : request.messages()) {
            String text = extractText(samplingMsg.content());
            messages.add(switch (samplingMsg.role()) {
                case USER -> new UserMessage(text);
                case ASSISTANT -> new AssistantMessage(text);
            });
        }

        return messages;
    }

    /**
     * MCP Content에서 텍스트를 추출.
     * TextContent 이외의 콘텐츠 타입(이미지 등)은 고정 문구로 대신한다.
     */
    private String extractText(McpSchema.Content content) {
        if (content instanceof McpSchema.TextContent textContent) {
            return textContent.text() != null ? textContent.text() : "";
        }
        return "[비텍스트 콘텐츠]";
    }
}
