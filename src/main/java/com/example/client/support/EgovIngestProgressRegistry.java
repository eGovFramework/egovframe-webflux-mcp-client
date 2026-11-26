package com.example.client.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 적재 작업(jobId)별 진행률 싱크 보관소.
 *
 * MCP 서버가 보낸 `notifications/progress` 를 브라우저 SSE 로 중계하기 위한 중간 버퍼다.
 * 흐름은 다음과 같다.
 *
 *   서버 ctx.progress(...)                  (EgovDocumentIngestServiceImpl.runPipeline)
 *     → notifications/progress              (_meta.progressToken = jobId)
 *     → @McpProgress 핸들러          (EgovMcpClientEventHandler)
 *     → 이 레지스트리의 jobId 싱크
 *     → GET /api/documents/ingest/progress/{jobId}  (SSE)
 *     → 브라우저 진행률 표시
 *
 * 서버 코드에서는 진행률을 보내고 있으나
 * 클라이언트 요청의 `_meta.progressToken` 이 없으면 서버는 진행률을 아예 보내지 않는다.
 *
 * mcp-annotations `DefaultMcpAsyncRequestContext.progress()` 가
 * `request.progressToken()` 이 비어 있으면 `ProgressNotification` 을
 * 만들지도 않고 `Mono.empty()` 로 끝내기 때문이다.
 *
 * 진행률이 안 오는 것이 곧 오류는 아니다. 다음 경우에는 진행률이 안 온다. (정상)
 *   1. 변경된 페이지가 없어 임베딩을 생략한 경우. 파이프라인에 진입하지 않는다.
 *   2. 인증 실패·시그니처 위반·인젝션 탐지·덮어쓰기 차단. 파이프라인 전에 거부된다.
 *   3. 범용 클라이언트(VS Code 등)가 호출한 경우. 이 레지스트리를 거치지 않는다.
 *
 * 또한 jobId 하나에 싱크 하나가 맵에 들어간다. 맵은 안 지우면 계속 쌓인다.
 *
 * 만드는 경로는 둘이다. POST 적재가 register 를 부를 때(적재 한 번에 하나), 그리고
 * 브라우저가 진행률 SSE 를 열 때(subscribe 가 내부에서 register 를 부른다). 후자는
 * 적재와 무관하게 호출될 수 있고 isValidJobId 가 모양만 검사하므로 개수 제한이 없다.
 *
 * `evictExpired()` 에 `@Scheduled` 를 붙여 주기적으로 돌리는 방법을 쓰지 않고,
 * `register`/`subscribe` 가 직접 호출하게 했다.
 *
 * 적재가 끝나면 `complete(jobId)` 가 불린다. 거기서는 싱크에 완료 신호만 넣고 맵에서
 * 지우지는 않는다.
 *
 * 이렇게 처리한 이유는 늦게 SSE 를 연 브라우저 때문이다.
 * 항목이 남아 있으면 `computeIfAbsent` 가 그 싱크를 그대로 돌려주므로, 
 * 브라우저는 지난 이벤트(replay 버퍼)와 완료 신호를 받아 진행률 표시를 마무리한다.
 * `complete` 에서 지웠다면 `computeIfAbsent` 가 빈 싱크를 새로 만들어 돌려주고, 
 * 적재는 이미 끝나 아무도 거기에 넣지 않으므로 브라우저는 (Time To Live) 청소가
 * 그 빈 싱크를 완료시킬 때까지(최대 10분) 매달린다.
 *
 * 늦게 열리는 경우: 적재 중 페이지 새로고침, `EventSource` 의 자동 재접속,
 * 진행률 URL 을 직접 여는 경우.
 */
@Slf4j
@Component
public class EgovIngestProgressRegistry {

    /**
     * jobId 허용 문자. 로그·SSE 경로 변수로 그대로 들어가므로 좁게 잡는다.
     *
     * 개행을 막아 로그 인젝션을 차단하고, 길이 상한으로 맵 키가 비대해지는 것을 막는다.
     * 브라우저가 crypto.randomUUID() 로 만드는 값이 이 범위에 들어온다.
     */
    private static final Pattern VALID_JOB_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /**
    * 싱크 보관 기한(Time To Live). 만든 지 이만큼 지나면 다음 register/subscribe 때 제거된다.
    * 적재 타임아웃(spring.ai.mcp.client.request-timeout, 3분)보다 넉넉하게 잡는다.
    */
    private static final Duration TTL = Duration.ofMinutes(10);

    /**
     * 늦게 붙은 구독자에게 되돌려 줄 이벤트 수.
     *
     * 파이프라인 단계가 4개뿐이므로 16 이면 전부 담긴다. 브라우저가 POST 보다 먼저
     * SSE 를 열도록 만들었지만, 그보다 늦게 붙어도 replay 가 지나간 이벤트를
     * 다시 내려준다.
     */
    private static final int REPLAY_SIZE = 16;

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    /**
     * 진행률 한 건. 서버가 보낸 값을 그대로 옮긴다.
     *
     * `message` 를 서버가 만들어 보내므로 브라우저는 단계 이름을 하드코딩하지 않아도 된다.
     * 단계 구성이 바뀌어도 UI 를 고칠 필요가 없다.
     *
     * @param progress 현재 단계 (서버 기준 1~4)
     * @param total    전체 단계 수. 서버가 생략하면 null
     * @param message  사람이 읽을 단계 설명 (예: "청킹 완료 (18개 청크)")
     */
    public record ProgressEvent(double progress, Double total, String message) {
    }

    /** 싱크와 등록 시각을 함께 들고 있는 항목. TTL 판정에 시각이 필요하다. */
    private record Job(Sinks.Many<ProgressEvent> sink, Instant createdAt) {
    }

    /**
     * jobId 가 경로 변수·로그에 그대로 실릴 수 있는 형태인지 검사한다.
     * 컨트롤러 두 곳(POST 적재, GET SSE)이 같은 기준을 쓰도록 여기에 둔다.
     */
    public static boolean isValidJobId(String jobId) {
        return jobId != null && VALID_JOB_ID.matcher(jobId).matches();
    }

    /**
     * jobId 의 싱크를 준비한다. 이미 있으면 그것을 돌려주므로 호출 순서에 무관.
     *
     * POST 적재와 SSE 구독 중 어느 쪽이 먼저 와도 같은 싱크를 공유해야 한다.
     * 호출할 때마다 TTL 이 지난 항목을 함께 삭제한다.
     */
    public Sinks.Many<ProgressEvent> register(String jobId) {
        evictExpired();
        return jobs.computeIfAbsent(jobId, id -> {
            log.debug("[진행률] 싱크 등록 - jobId: {}", id);
            return new Job(Sinks.many().replay().limit(REPLAY_SIZE), Instant.now());
        }).sink();
    }

    /**
     * 브라우저로 보낼 진행률 스트림을 돌려준다.
     * 싱크가 없으면 만들어 두므로, SSE 가 POST 보다 먼저 열려도 이벤트를 놓치지 않게 된다.
     */
    public Flux<ProgressEvent> subscribe(String jobId) {
        return register(jobId).asFlux();
    }

    /**
     * 서버가 보낸 진행률 알림을 해당 jobId 스트림에 넣는다.
     *
     * 모르는 토큰은 버린다. 범용 클라이언트가 호출한 적재, 채팅 경로의 적재,
     * TTL 이 지난 작업의 지연 알림이 모두 여기로 올 수 있고 어느 것도 오류는 아니다.
     *
     * @return 해당 jobId 싱크에 실제로 넣었으면 true
     */
    public boolean emit(McpSchema.ProgressNotification notification) {
        Object token = notification.progressToken();
        if (token == null) {
            return false;
        }

        String jobId = token.toString();
        Job job = jobs.get(jobId);
        if (job == null) {
            log.debug("[진행률] 구독자 없는 토큰 - 버림 (token: {})", jobId);
            return false;
        }

        double progress = notification.progress() != null ? notification.progress() : 0d;
        job.sink().tryEmitNext(new ProgressEvent(progress, notification.total(), notification.message()));
        return true;
    }

    /**
     * 작업이 끝났음을 스트림에 알린다(성공·실패 공통).
     *
     * 맵에서 바로 지우지 않는다. 늦게 붙은 구독자가 replay 와 `onComplete` 를
     * 받아야 하므로 TTL 까지 남겨 두고 `evictExpired` 에 맡긴다.
     *
     * 스트림이 닫히면 `EventSource` 는 자동 재접속을 시도한다. 브라우저 쪽에서
     * POST 응답을 받은 시점에 `close()` 를 호출해 끊어야 한다.
     */
    public void complete(String jobId) {
        Job job = jobs.get(jobId);
        if (job != null) {
            job.sink().tryEmitComplete();
            log.debug("[진행률] 스트림 종료 - jobId: {}", jobId);
        }
    }

    /** 현재 보관 중인 작업 수. 누수 점검용. */
    public int size() {
        return jobs.size();
    }

    /**
     * TTL이 지난 항목을 제거한다.
     *
     * 남은 구독자가 있어도 지운다. TTL(10분)은 적재 타임아웃보다 길게 잡은 값이므로
     * 그 시점의 스트림은 이미 의미가 없고, 붙잡아 두면 그것이 누수다.
     */
    private void evictExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        Iterator<Map.Entry<String, Job>> it = jobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Job> entry = it.next();
            if (entry.getValue().createdAt().isBefore(cutoff)) {
                entry.getValue().sink().tryEmitComplete();
                it.remove();
                log.debug("[진행률] TTL 만료 - 싱크 제거 (jobId: {})", entry.getKey());
            }
        }
    }
}
