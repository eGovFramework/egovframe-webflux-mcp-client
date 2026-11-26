package com.example.client.controller;

/**
 * MCP 도구가 `isError = true` 로 응답했을 때 사용하는 예외.
 *
 * 서버는 인증 실패·덮어쓰기 차단·인젝션 탐지·미지원 확장자 같은 거부를
 * JSON-RPC 오류가 아니라 정상 응답 + `isError` 플래그로 전달하는 것을 확인
 * (mcp-annotations AbstractAsyncMcpToolMethodCallback.createAsyncErrorResult).
 * 호출부에서 해당 값을 직접 보고 오류로 전환해야 화면에 실패로 표시된다.
 */
public class McpToolCallFailedException extends RuntimeException {

    public McpToolCallFailedException(String message) {
        super(message);
    }
}
