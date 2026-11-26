package com.example.client.support;

import java.util.Map;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 채팅 SSE 이벤트를 만든다. 브라우저로 나가는 두 종류가 모두 여기서 만들어진다.
 *
 *   event:token                                              LLM 답변 텍스트 조각
 *   data:{"text":"안녕하세요"}
 *
 *   event:status                                             도구 호출 상태
 *   data:{"type":"tool_calling","tool":"searchDocuments"}
 *
 * 이벤트를 만드는 곳이 셋이다. EgovChatServiceImpl(답변 텍스트·MCP 미연결 경고),
 * EgovChatController(세션 오류 안내), StatusEmittingToolCallback(도구 상태).
 *
 * 그런데 chat.html 에서는 들어오는 data 를 JSON 으로 가정하고, JSON.parse 가 실패하면
 * 버리게 되어 있다. 형식을 어기게 되면 화면에 안 뜨게 되고, 원인을 찾기 어려워 해당 클래스에서
 * 생성 방식을 통일한다.
 *
 * JSON은 ObjectMapper 가 만든다. 문자열을 손으로 붙이면 제어문자(개행·탭 등)
 * 이스케이프를 빠뜨리기 쉽다.
 */
public final class EgovChatSseEvents {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EgovChatSseEvents() {
    }

    /**
     * LLM 응답 청크를 token 이벤트로 변환한다.
     *
     * 텍스트가 없는 청크는 빈 문자열로 내려간다. 도구 호출 구간에서 그런 청크가 오는데,
     * 브라우저가 빈 값을 건너뛰므로 상태 표시가 지워지지 않는다.
     */
    public static ServerSentEvent<String> token(ChatResponse cr) {
        String text = cr.getResult() != null && cr.getResult().getOutput() != null
                ? cr.getResult().getOutput().getText() : "";
        return token(text != null ? text : "");
    }

    /** 텍스트를 token 이벤트로 감싼다. 공백 보존 때문에 JSON 으로 싼다(클래스 주석 참고). */
    public static ServerSentEvent<String> token(String text) {
        try {
            String data = MAPPER.writeValueAsString(Map.of("text", text != null ? text : ""));
            return ServerSentEvent.<String>builder().event("token").data(data).build();
        } catch (JsonProcessingException e) {
            return ServerSentEvent.<String>builder().event("token").data("{\"text\":\"\"}").build();
        }
    }

    /**
     * 도구 호출 상태를 status 이벤트로 변환한다.
     *
     * @param type     tool_calling / tool_done / tool_error
     * @param toolName 도구 이름
     */
    public static ServerSentEvent<String> status(String type, String toolName) {
        try {
            String data = MAPPER.writeValueAsString(Map.of(
                    "type", type != null ? type : "",
                    "tool", toolName != null ? toolName : ""));
            return ServerSentEvent.<String>builder().event("status").data(data).build();
        } catch (JsonProcessingException e) {
            return ServerSentEvent.<String>builder().event("status")
                    .data("{\"type\":\"\",\"tool\":\"\"}").build();
        }
    }
}
