package com.example.client.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.client.config.EgovMcpServerRegistry;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RAG 지시문을 MCP 서버의 `@McpPrompt rag_assistant`에서 받아온다.
 *
 * 지시문을 클라이언트에 하드코딩하지 않고 MCP Prompt 로 받도록 처리하였다.
 *
 * 실제로 프롬프트에 싣는 일은 `EgovRagGuidanceAdvisor` 가 맡아 마지막 user 메시지에 합친다.
 * 별도 메시지로 두면 소형 모델에서 정확한 동작을 하지 않았던 이슈 때문이다.
 *
 * 서버가 주는 role 은 `assistant` 고정이지만 쓰지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovRagPromptProvider {

    private static final String SERVER_NAME = "rag-server";
    private static final String PROMPT_NAME = "rag_assistant";

    private final EgovMcpServerRegistry mcpServerRegistry;

    /** 서버 프롬프트의 strict 인자. true 면 모든 질문에 검색을 강제한다. */
    @Value("${app.rag.prompt-strict:false}")
    private String strict;

    /**
     * 서버에서 받아온 지시문 캐시. 요청마다 서버에 다시 묻지 않는다.
     *
     * 캐시는 연결이 유지될 때만 유효하다. 연결이 끊기면
     * `guidanceText()` 가 캐시를 폐기한다.
     */
    private final AtomicReference<String> cached = new AtomicReference<>();

    /**
     * 프롬프트에 실을 지시문 원문. 서버에서 받지 못하면 null.
     *
     * 지시문의 출처가 서버이므로, 지시문을 받았다는 것은 서버가 연결돼 있고
     * 도구를 쓸 수 있다는 뜻이다.
     */
    public String guidanceText() {
        // 캐시보다 연결 상태를 먼저 본다.
        // 캐시된 지시문을 무조건 쓰면 지시문이 있어도 서버가 끊겨 있을 수 있다.
        // getClient() 는 status != CONNECTED 이면 null 이다. getToolCallbacks() 가
        // 매 요청 앞단에서 listTools() 호출이 실패한 서버를 FAILED 로 바꾸므로,
        // 이 시점의 연결 상태는 최신이다.
        McpAsyncClient client = mcpServerRegistry.getClient(SERVER_NAME);
        if (client == null) {
            if (cached.getAndSet(null) != null) {
                log.info("[RAG지시문] '{}' 연결 끊김 - 캐시 폐기, 지시문 없이 진행", SERVER_NAME);
            } else {
                log.warn("[RAG지시문] '{}' 미연결 - 지시문 없이 진행", SERVER_NAME);
            }
            return null;
        }

        String text = cached.get();
        if (text == null) {
            text = fetchFromServer(client);
            if (text == null) {
                return null;
            }
            cached.set(text);
        }
        return text;
    }

    /** MCP `prompts/get` 호출. 실패 시 null. */
    private String fetchFromServer(McpAsyncClient client) {
        try {
            McpSchema.GetPromptResult result = client
                    .getPrompt(new McpSchema.GetPromptRequest(PROMPT_NAME, Map.of("strict", strict)))
                    .block(Duration.ofSeconds(5));
            if (result == null || result.messages() == null || result.messages().isEmpty()) {
                log.warn("[RAG지시문] '{}' 응답이 비어 있음", PROMPT_NAME);
                return null;
            }
            String text = joinText(result.messages());
            // 서버 role 은 항상 assistant 다. mcp-annotations 가 String 반환을
            // PromptMessage(Role.ASSISTANT, ...) 로 감싸기 때문에 서버가 고를 수 없다.
            // 우리는 role 을 쓰지 않고 텍스트만 꺼내 마지막 user 메시지에 합친다
            // (EgovRagGuidanceAdvisor 참조).
            log.info("[RAG지시문] 서버 프롬프트 수신 - {}자, 서버 role={}",
                    text.length(),
                    result.messages().stream().map(m -> String.valueOf(m.role()))
                            .collect(Collectors.joining(",")));
            return text;
        } catch (Exception e) {
            log.warn("[RAG지시문] '{}' 조회 실패: {}", PROMPT_NAME, e.getMessage());
            return null;
        }
    }

    private static String joinText(List<McpSchema.PromptMessage> messages) {
        return messages.stream()
                .map(McpSchema.PromptMessage::content)
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"))
                .strip();
    }
}
