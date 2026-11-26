package com.example.client.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.client.support.EgovIngestProgressRegistry.ProgressEvent;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * EgovIngestProgressRegistry 단위 테스트.
 *
 * Spring 컨텍스트 없이 돌린다. 레지스트리는 상태를 들고 있을 뿐 주입받는 것이 없다.
 * reactor-test(StepVerifier) 를 쓰지 않고 collectList().block() 으로
 * 확인한다. 의존성을 새로 추가하지 않기 위한 선택이다.
 */
class EgovIngestProgressRegistryTest {

    private final EgovIngestProgressRegistry registry = new EgovIngestProgressRegistry();

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    // jobId 검증

    @Test
    @DisplayName("브라우저가 만드는 UUID 형식 jobId 를 허용한다")
    void acceptsUuidJobId() {
        assertThat(EgovIngestProgressRegistry.isValidJobId("3f2504e0-4f89-11d3-9a0c-0305e82c3301")).isTrue();
        assertThat(EgovIngestProgressRegistry.isValidJobId("ingest-1738291847123")).isTrue();
        assertThat(EgovIngestProgressRegistry.isValidJobId("A1")).isTrue();
    }

    @Test
    @DisplayName("개행이 섞인 jobId 를 거부한다 - 로그 인젝션 차단")
    void rejectsJobIdWithNewline() {
        assertThat(EgovIngestProgressRegistry.isValidJobId("job\n[적재] 위조된 로그 줄")).isFalse();
        assertThat(EgovIngestProgressRegistry.isValidJobId("job\r\n")).isFalse();
    }

    @Test
    @DisplayName("빈 값·공백·경로 문자·64자 초과를 거부한다")
    void rejectsMalformedJobId() {
        assertThat(EgovIngestProgressRegistry.isValidJobId(null)).isFalse();
        assertThat(EgovIngestProgressRegistry.isValidJobId("")).isFalse();
        assertThat(EgovIngestProgressRegistry.isValidJobId("job id")).isFalse();
        assertThat(EgovIngestProgressRegistry.isValidJobId("../../etc/passwd")).isFalse();
        assertThat(EgovIngestProgressRegistry.isValidJobId("a".repeat(65))).isFalse();
        assertThat(EgovIngestProgressRegistry.isValidJobId("a".repeat(64))).isTrue();
    }

    // 상관관계: progressToken 으로 스트림을 고른다

    @Test
    @DisplayName("progressToken 과 같은 jobId 스트림으로만 흘러간다")
    void routesByProgressToken() {
        registry.register("job-A");
        registry.register("job-B");

        assertThat(registry.emit(progress("job-A", 1, "텍스트 추출 완료"))).isTrue();
        assertThat(registry.emit(progress("job-B", 1, "텍스트 추출 완료"))).isTrue();
        assertThat(registry.emit(progress("job-A", 2, "요약 완료"))).isTrue();

        registry.complete("job-A");
        registry.complete("job-B");

        assertThat(messagesOf("job-A")).containsExactly("텍스트 추출 완료", "요약 완료");
        assertThat(messagesOf("job-B")).containsExactly("텍스트 추출 완료");
    }

    @Test
    @DisplayName("등록되지 않은 토큰은 버린다 - 범용 클라이언트·채팅 경로 적재")
    void dropsUnknownToken() {
        assertThat(registry.emit(progress("남의-작업", 1, "텍스트 추출 완료"))).isFalse();
    }

    @Test
    @DisplayName("progressToken 이 없는 알림은 버린다")
    void dropsNullToken() {
        McpSchema.ProgressNotification noToken =
                new McpSchema.ProgressNotification(null, 1d, 4d, "텍스트 추출 완료", null);
        assertThat(registry.emit(noToken)).isFalse();
    }

    // 순서 독립: POST 와 SSE 중 어느 쪽이 먼저 와도 된다

    @Test
    @DisplayName("구독보다 먼저 온 진행률도 replay 로 전달된다")
    void replaysEventsEmittedBeforeSubscribe() {
        registry.register("job-1");

        registry.emit(progress("job-1", 1, "텍스트 추출 완료"));
        registry.emit(progress("job-1", 2, "요약 완료"));
        registry.complete("job-1");

        // 모든 이벤트가 끝난 뒤에 구독한다. 브라우저 SSE 가 늦게 열린 상황.
        assertThat(messagesOf("job-1")).containsExactly("텍스트 추출 완료", "요약 완료");
    }

    @Test
    @DisplayName("SSE 가 POST 보다 먼저 열려도 같은 싱크를 공유한다")
    void subscribeBeforeRegisterSharesSink() {
        // subscribe 가 먼저 일어난다. 싱크를 만들어 둔다.
        var stream = registry.subscribe("job-2");
        // 이후 적재가 register 를 호출해도 새 싱크를 만들지 않는다.
        registry.register("job-2");

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.emit(progress("job-2", 1, "텍스트 추출 완료"))).isTrue();
        registry.complete("job-2");

        assertThat(stream.collectList().block(TIMEOUT))
                .extracting(ProgressEvent::message)
                .containsExactly("텍스트 추출 완료");
    }

    @Test
    @DisplayName("같은 jobId 를 두 번 등록해도 싱크는 하나다")
    void registerIsIdempotent() {
        var first = registry.register("job-3");
        var second = registry.register("job-3");

        assertThat(first).isSameAs(second);
        assertThat(registry.size()).isEqualTo(1);
    }

    // 값 전달: UI 가 하드코딩 없이 그릴 수 있어야 한다

    @Test
    @DisplayName("progress·total·message 를 그대로 옮긴다")
    void carriesProgressTotalAndMessage() {
        registry.register("job-4");
        registry.emit(progress("job-4", 3, "청킹 완료 (18개 청크)"));
        registry.complete("job-4");

        List<ProgressEvent> events = registry.subscribe("job-4").collectList().block(TIMEOUT);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).progress()).isEqualTo(3d);
        assertThat(events.get(0).total()).isEqualTo(4d);
        assertThat(events.get(0).message()).isEqualTo("청킹 완료 (18개 청크)");
    }

    @Test
    @DisplayName("progress 가 null 이면 0 으로 채운다 - UI 계산이 깨지지 않게")
    void defaultsNullProgressToZero() {
        registry.register("job-5");
        registry.emit(new McpSchema.ProgressNotification("job-5", null, 4d, "시작", null));
        registry.complete("job-5");

        List<ProgressEvent> events = registry.subscribe("job-5").collectList().block(TIMEOUT);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).progress()).isZero();
    }

    // 종료

    @Test
    @DisplayName("complete 전에는 스트림이 열려 있다")
    void streamStaysOpenUntilComplete() {
        registry.register("job-6");
        registry.emit(progress("job-6", 1, "텍스트 추출 완료"));

        // complete 를 부르지 않았으므로 완료되지 않는다. 짧은 대기 후 지금까지 받은 것만 꺼낸다.
        List<ProgressEvent> received = registry.subscribe("job-6")
                .take(Duration.ofMillis(200))
                .collectList()
                .block(TIMEOUT);

        assertThat(received).extracting(ProgressEvent::message).containsExactly("텍스트 추출 완료");
    }

    @Test
    @DisplayName("등록되지 않은 jobId 에 complete 를 불러도 예외가 나지 않는다")
    void completeOnUnknownJobIsNoop() {
        registry.complete("없는-작업");
        assertThat(registry.size()).isZero();
    }

    // helpers

    /** 서버 runPipeline 이 보내는 형태의 알림을 만든다 (total 은 항상 4단계). */
    private McpSchema.ProgressNotification progress(String token, double step, String message) {
        return new McpSchema.ProgressNotification(token, step, 4d, message, null);
    }

    private List<String> messagesOf(String jobId) {
        List<ProgressEvent> events = registry.subscribe(jobId).collectList().block(TIMEOUT);
        return events == null ? List.of() : events.stream().map(ProgressEvent::message).toList();
    }
}
