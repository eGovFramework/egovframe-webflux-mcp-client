package com.example.client.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.client.config.EgovMcpServerRegistry;
import com.example.client.support.EgovIngestProgressRegistry;
import com.example.client.support.EgovIngestProgressRegistry.ProgressEvent;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * EgovDocumentIngestController 슬라이스 테스트.
 *
 * MCP 클라이언트는 연결되지 않은 상태(getClient 가 null)로 둔다. 확인하려는 것은
 * MCP 호출이 아니라 컨트롤러가 값을 넘기고 응답을 만드는 과정이다.
 *
 * <ul>
 *   <li>멀티파트 본문과 함께 보낸 jobId 쿼리 파라미터가 바인딩되는가.
 *       @RequestPart String 은 이 경로에서 415 가 되므로 쿼리 파라미터로 받는다
 *       (컨트롤러 Javadoc 참조).</li>
 *   <li>jobId 가 없을 때 서버가 만들어 쓰는가 (curl 하위 호환).</li>
 *   <li>형식에서 벗어난 jobId 를 거부하는가.</li>
 *   <li>진행률 SSE 가 해당 jobId 의 이벤트를 전달하는가.</li>
 * </ul>
 *
 * 진행률 레지스트리는 모의가 아니라 실물을 쓴다. 상태만 들고 있는 순수 객체라
 * 모의로 바꾸면 검증 가치가 줄어든다.
 */
class EgovDocumentIngestControllerTest {

    private EgovIngestProgressRegistry progressRegistry;
    private EgovMcpServerRegistry mcpServerRegistry;
    private WebTestClient webTestClient;

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @BeforeEach
    void setUp() {
        progressRegistry = new EgovIngestProgressRegistry();
        mcpServerRegistry = mock(EgovMcpServerRegistry.class);
        // MCP 미연결. 적재는 거부되지만 jobId 처리까지는 그대로 통과한다.
        when(mcpServerRegistry.getClient("rag-server")).thenReturn(null);

        webTestClient = WebTestClient
                .bindToController(new EgovDocumentIngestController(mcpServerRegistry, progressRegistry))
                .configureClient()
                .responseTimeout(TIMEOUT)
                .build();
    }

    // jobId 바인딩

    @Test
    @DisplayName("멀티파트 업로드와 함께 보낸 jobId 쿼리 파라미터가 바인딩되어 응답에 돌아온다")
    void bindsJobIdFromQueryParam() {
        String jobId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

        Map<String, Object> body = postIngest("guide.md", jobId);

        assertThat(body.get("jobId")).isEqualTo(jobId);
        // MCP 미연결이므로 적재 자체는 실패한다. 확인하려는 것은 jobId 가 응답에 그대로 실리는지다.
        assertThat(body.get("success")).isEqualTo(false);
        assertThat((String) body.get("message")).contains("MCP 서버가 연결되지 않았습니다");
    }

    @Test
    @DisplayName("jobId 를 보내지 않으면 서버가 UUID 를 만들어 쓴다 - curl 하위 호환")
    void generatesJobIdWhenAbsent() {
        Map<String, Object> body = postIngest("guide.md", null);

        String jobId = (String) body.get("jobId");
        assertThat(jobId).isNotNull();
        assertThat(EgovIngestProgressRegistry.isValidJobId(jobId)).isTrue();
        // UUID 형식인지까지 확인한다.
        assertThat(UUID.fromString(jobId)).isNotNull();
    }

    @Test
    @DisplayName("형식에서 벗어난 jobId 를 거부한다")
    void rejectsMalformedJobId() {
        Map<String, Object> body = postIngest("guide.md", "job id\n[적재] 위조된 줄");

        assertThat(body.get("success")).isEqualTo(false);
        assertThat((String) body.get("message")).contains("jobId 형식이 올바르지 않습니다");
        // 거부된 값은 응답에도 담지 않는다.
        assertThat(body).doesNotContainKey("jobId");
    }

    @Test
    @DisplayName("지원하지 않는 확장자는 거부하면서도 jobId 를 함께 돌려준다")
    void rejectsUnsupportedExtensionWithJobId() {
        String jobId = "job-unsupported";

        Map<String, Object> body = postIngest("malware.exe", jobId);

        assertThat(body.get("success")).isEqualTo(false);
        assertThat(body.get("jobId")).isEqualTo(jobId);
        assertThat((String) body.get("message")).contains("파일만 지원합니다");
    }

    @Test
    @DisplayName("실패 응답을 돌려줄 때 열려 있던 진행률 스트림도 닫는다")
    void closesProgressStreamOnFailure() {
        String jobId = "job-closed";
        // 브라우저가 POST 보다 먼저 SSE 를 연 상황을 만든다.
        progressRegistry.register(jobId);

        postIngest("guide.md", jobId);

        // complete 되었으므로 구독이 바로 끝난다. 닫히지 않았다면 여기서 타임아웃이다.
        List<ProgressEvent> events = progressRegistry.subscribe(jobId).collectList().block(TIMEOUT);
        assertThat(events).isEmpty();
    }

    // 진행률 SSE

    @Test
    @DisplayName("진행률 SSE 가 해당 jobId 의 이벤트를 JSON 으로 전달한다")
    void streamsProgressForJobId() {
        String jobId = "job-sse";
        progressRegistry.register(jobId);
        progressRegistry.emit(new McpSchema.ProgressNotification(jobId, 1d, 4d, "텍스트 추출 완료 (3페이지)", null));
        progressRegistry.emit(new McpSchema.ProgressNotification(jobId, 2d, 4d, "요약 완료", null));
        progressRegistry.complete(jobId);

        List<ProgressEvent> events = webTestClient.get()
                .uri("/api/documents/ingest/progress/{jobId}", jobId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(ProgressEvent.class)
                .getResponseBody()
                .collectList()
                .block(TIMEOUT);

        assertThat(events).extracting(ProgressEvent::message)
                .containsExactly("텍스트 추출 완료 (3페이지)", "요약 완료");
        assertThat(events).extracting(ProgressEvent::progress).containsExactly(1d, 2d);
        assertThat(events).extracting(ProgressEvent::total).containsExactly(4d, 4d);
    }

    @Test
    @DisplayName("형식에서 벗어난 jobId 로 SSE 를 열면 빈 스트림을 준다 - 싱크를 만들지 않는다")
    void sseWithMalformedJobIdYieldsEmptyStream() {
        List<ProgressEvent> events = webTestClient.get()
                .uri("/api/documents/ingest/progress/{jobId}", "../../etc/passwd")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(ProgressEvent.class)
                .getResponseBody()
                .collectList()
                .block(TIMEOUT);

        assertThat(events).isEmpty();
        assertThat(progressRegistry.size()).isZero();
    }

    // helpers

    /**
     * 브라우저와 같은 모양으로 보낸다. 파일 하나만 멀티파트 본문에 담고
     * jobId 는 쿼리 파라미터로 붙인다.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postIngest(String filename, String jobId) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource("# 제목\n본문".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        MultiValueMap<String, ?> parts = builder.build();

        URI uri = jobId == null
                ? URI.create("/api/documents/ingest")
                : UriComponentsBuilder.fromPath("/api/documents/ingest")
                        .queryParam("jobId", jobId)
                        .encode()
                        .build()
                        .toUri();

        return (Map<String, Object>) webTestClient.post()
                .uri(uri)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(parts)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
    }
}
