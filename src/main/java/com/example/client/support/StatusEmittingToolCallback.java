package com.example.client.support;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.codec.ServerSentEvent;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Sinks;

/**
 * ToolCallback 을 감싸 도구 호출 전후 상태 이벤트를 statusSink 에 넣는다.
 * statusSink 는 SSE 응답 Flux 에 병합되어 프론트엔드에 도구 호출 상태를 전달한다.
 * 이벤트 생성은 EgovChatSseEvents.status() 가 맡는다.
 */
@Slf4j
public class StatusEmittingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final Sinks.Many<ServerSentEvent<String>> statusSink;

    public StatusEmittingToolCallback(ToolCallback delegate, Sinks.Many<ServerSentEvent<String>> statusSink) {
        this.delegate = delegate;
        this.statusSink = statusSink;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String name = delegate.getToolDefinition().name();
        log.debug("도구 호출 시작: {}", name);
        emit("tool_calling", name);
        try {
            String result = delegate.call(toolInput);
            emit("tool_done", name);
            return result;
        } catch (Exception e) {
            emit("tool_error", name);
            throw e;
        }
    }

    private void emit(String type, String toolName) {
        statusSink.tryEmitNext(EgovChatSseEvents.status(type, toolName));
    }
}
