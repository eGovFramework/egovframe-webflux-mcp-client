package com.example.client.support;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.Assert;

import lombok.extern.slf4j.Slf4j;

/**
 * RAG 지시문을 마지막 사용자 메시지에 합쳐 모델에 전달하는 어드바이저.
 *
 * 소형 모델은 별도 메시지로 떨어진 지시문을 따르지 않기 때문이다.
 *
 * 합치는 작업을 `.user(...)` 단계에서 하면 저장되는 메시지에 지시문이 섞여 버리고 
 * 화면에 표시할 때 지시문을 도로 지워야 했다. 또한 이력에 남은 지시문 때문에
 * RAG 체크를 풀어도 계속 검색을 시도하는 현상이 있었다. 그래서 저장이 끝난 뒤에
 * 실행되는 어드바이저로 만들었다.
 *
 * 지시문 원문은 서버의 `@McpPrompt rag_assistant` 에서 온다.
 * `EgovRagPromptProvider` 클래스를 참조.
 */
@Slf4j
public class EgovRagGuidanceAdvisor implements BaseAdvisor {

    /** `MessageChatMemoryAdvisor`(최솟값)보다 뒤에 실행되기만 하면 된다. */
    public static final int ORDER = 0;

    private final String guidance;

    public EgovRagGuidanceAdvisor(String guidance) {
        Assert.hasText(guidance, "guidance must not be empty");
        this.guidance = guidance;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        List<Message> messages = new ArrayList<>(request.prompt().getInstructions());

        // 마지막 UserMessage 를 찾아 지시문을 앞에 붙인다.
        // 이력 복원으로 과거 UserMessage 들이 앞에 실려 있을 수 있으므로 뒤에서부터 찾는다.
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                String merged = guidance + "\n" + user.getText();
                log.debug("[RAG지시문] 합침 - 지시문 {}자 + 질문 {}자 = {}자 (메시지 {}개 중 {}번)",
                        guidance.length(), user.getText().length(), merged.length(), messages.size(), i);
                messages.set(i, new UserMessage(merged));
                return request.mutate()
                        .prompt(request.prompt().mutate().messages(messages).build())
                        .build();
            }
        }
        // UserMessage 가 없으면(도구 응답만 있는 후속 턴 등) 요청을 그대로 둔다.
        log.warn("[RAG지시문] 프롬프트에 UserMessage 가 없어 지시문을 싣지 못함 - 메시지 {}개", messages.size());
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
