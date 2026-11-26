package com.example.client.controller;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.client.service.EgovChatService;
import com.example.client.service.EgovChatSessionService;
import com.example.client.support.EgovChatSseEvents;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class EgovChatController {

    private final EgovChatService chatService;
    private final EgovChatSessionService chatSessionService;


    /**
     * 일반 스트리밍 응답 생성 (전체 MCP 도구 제공, LLM이 호출 여부 자율 판단)
     */
    @GetMapping(value = "/simple/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamSimpleResponse(
            @RequestParam(value = "message", defaultValue = "Tell me about this document") String message,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "ragMode", defaultValue = "true") boolean ragMode) {
        // 질문 본문은 로그에 남기지 않는다(EgovChatServiceImpl 과 같은 기준). 길이만 남긴다.
        log.info("일반 스트리밍 질의 수신 - 질문 {}자, 모델: {}, RAG모드: {}, 세션: {}",
                message != null ? message.length() : 0, model, ragMode, sessionId);

        return resolveSessionId(sessionId, message)
                .flatMapMany(resolvedId -> chatService.streamSimpleResponse(message, model, resolvedId, ragMode))
                // 세션 검증 실패를 정상 스트림의 token 이벤트로 바꿔 내려보낸다.
                // data 형식은 EgovChatSseEvents 가 처리한다.
                .onErrorResume(IllegalArgumentException.class,
                        e -> Flux.just(EgovChatSseEvents.token(e.getMessage())));
    }

    /**
     * 세션 ID를 확인하고 첫 메시지면 제목을 자동 생성.
     * 블로킹 JPA 호출이 있으므로 boundedElastic 스케줄러에서 실행.
     *
     * Spring AI 1.1.7부터 ChatMemory.CONVERSATION_ID는 advisor param 키 이름일 뿐
     * 기본 대화 ID 값이 아니므로(공식 문서: "There is no default conversation ID"),
     * 유효하지 않은 sessionId 는 기본값으로 대체하지 않고 IllegalArgumentException 을 던진다.
     *
     * @return 실제 사용할 세션 ID (Mono), 유효하지 않으면 IllegalArgumentException
     */
    private Mono<String> resolveSessionId(String sessionId, String message) {
        return Mono.fromCallable(() -> {
                    if (sessionId == null || sessionId.isEmpty()) {
                        throw new IllegalArgumentException("세션 ID가 제공되지 않았습니다. 새 세션을 먼저 생성하세요.");
                    }

                    if (!chatSessionService.sessionExists(sessionId)) {
                        throw new IllegalArgumentException("존재하지 않는 세션입니다: " + sessionId);
                    }

                    // 첫 메시지인 경우 세션 제목 업데이트
                    if (chatSessionService.getSessionMessages(sessionId).isEmpty()) {
                        String title = chatSessionService.generateSessionTitle(message);
                        chatSessionService.updateSessionTitle(sessionId, title);
                    } else {
                        chatSessionService.updateLastMessageTime(sessionId);
                    }

                    return sessionId;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

}
