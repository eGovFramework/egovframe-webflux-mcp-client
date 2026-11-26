package com.example.client.service;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 세션별 대화 기능을 제공하는 채팅 서비스 인터페이스
 */
public interface EgovChatService {

    /**
     * 스트리밍 응답 생성. 연결된 MCP 도구를 전달하고 호출 여부는 LLM 이 판단한다.
     * 
     * 반환하는 Flux 는 두 종류의 SSE 이벤트를 혼합한다:
     * event: status 는 도구 호출 상태 JSON
     * event: token 은 LLM 텍스트 청크
     *
     * @param ragMode true 면 시스템 프롬프트에 문서 검색 지시를 덧붙인다.
     *                소형 로컬 모델은 이 지시가 없으면 도구를 호출하지 않는 현상이 있어 추가했다.
     */
    Flux<ServerSentEvent<String>> streamSimpleResponse(String message, String model, String sessionId, boolean ragMode);
}
