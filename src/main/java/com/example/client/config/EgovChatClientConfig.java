package com.example.client.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * 채팅에 필요한 빈 세 개를 만든다.
 *
 * 이 클래스를 지우면 서버가 뜨지 않는다. ChatClient 와 MessageChatMemoryAdvisor를 여기서 만들고
 * EgovChatServiceImpl 에서 이 둘을 주입받기 때문이다.
 *
 * Spring AI 자동 구성이 대체 가능한지의 여부는 다음과 같다.
 *
 * ChatClient : 자동 구성은 ChatClient.Builder 까지만 만든다.
 * MessageChatMemoryAdvisor : 자동 구성에 없다.
 * ChatMemory : 자동 구성이 만들어 준다. 여기서 다시 만드는 건 보관할 메시지 수를 바꾸고
 *              저장 위치를 JdbcChatMemoryRepository 로 코드에 명시하기 위해서다.
 *
 *
 * 메시지는 spring_ai_chat_memory 테이블에만 있다.
 * 이 앱의 spring_ai_chat_sessions 테이블에는 제목과 시각만 들어간다.
 *
 * 저장소로 JdbcChatMemoryRepository(구현체)를 받는다. 자동 구성은
 * ChatMemoryRepository(인터페이스)를 받지만, 여기서 인터페이스로 열어 두면
 * JDBC 스타터가 빠질 때 자동 구성의 InMemoryChatMemoryRepository 로 변경되게 된다.
 * 그러면 "재기동하면 대화가 사라진다" 는 현상이 발생하게 되므로,
 * 구현체로 명시하여 기동 시점에 드러나게 한다.
 */
@Slf4j
@Configuration
public class EgovChatClientConfig {

    /**
     * 보관할 메시지 수를 바꾸고 저장 위치를 JdbcChatMemoryRepository 로 코드에 명시하려고
     * 자동 구성 빈을 대신 만든다.
     *
     * 저장 위치는 chatMemoryRepository 파라미터가 정하는데 
     * JdbcChatMemoryRepository 를 받으므로 DB 에 쌓인다.
     * 그래서 앱을 재기동해도 이력은 남는다.
     *
     * 자동 구성을 그냥 쓰면 MessageWindowChatMemory 의 기본값인 20개 가
     * 된다. 그 값은 `private static final` 이고 이를 바꿀 프로퍼티도 없어서,
     * 숫자를 다르게 하려면 빈을 직접 만드는 수밖에 없다.
     *
     * 한도를 넘은 메시지는 DB 에서 지워진다. 안 보여주는 게 아니라 삭제다.
     *
     */
    @Bean
    public ChatMemory chatMemory(
            JdbcChatMemoryRepository chatMemoryRepository,
            @Value("${app.chat.memory.max-messages:50}") int chatMemoryMaxMessages) {
        log.info("JDBC 기반 ChatMemory 생성 (PostgreSQL 영속화, 최대 {}개 메시지 유지)", chatMemoryMaxMessages);
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(chatMemoryMaxMessages)
                .build();
    }

    /**
     * 앞 대화를 프롬프트에 끼워 넣고, 주고받은 메시지를 저장소에 쓴다.
     *
     * 이 앱에서 대화 내용을 저장하는 유일한 경로다(클래스 주석 참고).
     * ChatClient 에 기본으로 달지 않고 빈으로만 꺼내 둔다. 세션 id 를 파라미터로
     * 넘겨야 해서 EgovChatServiceImpl 이 요청 시점에 붙인다.
     */
    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    /**
     * 어드바이저를 하나도 달지 않은 ChatClient.
     *
     * defaultAdvisors(...)를 쓰지 않는다. 어드바이저 구성이 요청마다 다르기
     * 때문이다. 화면 단의 RAG 체크박스에 따라 안내 어드바이저가 붙거나 빠진다. 
     * 기본값으로 고정하면 특정 요청에서 빼는 수단이 없다. 어드바이저를 고르는 일은 EgovChatServiceImpl.chat() 이 한다.
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        log.info("ChatClient 구성: 기본 어드바이저 없이 생성 (세션별 동적 추가)");
        return ChatClient.builder(chatModel)
                .build();
    }
}
