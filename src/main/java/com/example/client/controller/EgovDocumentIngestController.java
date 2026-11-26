package com.example.client.controller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.example.client.config.EgovMcpServerRegistry;
import com.example.client.support.EgovIngestProgressRegistry;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * 클라이언트 로컬 문서를 서버 RAG DB에 적재(임베딩)하는 컨트롤러.
 *
 * 엔드포인트:
 *   POST /api/documents/ingest                      파일 적재 → MCP ingestDocument 호출 → 완료 결과 반환
 *   GET  /api/documents/ingest/progress/{jobId}     적재 진행률 SSE (선택)
 *
 * ingest.html 이 POST 보다 먼저 열어 1단계를 놓치지 않게 하지만,
 * 순서는 요구사항이 아니다. 레지스트리의 register 가 멱등이고 replay 버퍼가 있어
 * POST 뒤에 열어도 지난 이벤트를 받는다. curl 로는 POST 단독 호출이 정상이다.
 *
 * 멀티파트 단건만 받는다(@RequestPart("file") FilePart, 단수).
 *
 * 지원 형식: PDF (.pdf), DOCX (.docx), HWP (.hwp), HWPX (.hwpx), 마크다운 (.md), 텍스트 (.txt)
 * 바이너리(PDF·DOCX·HWP·HWPX)는 Base64 로, 텍스트는 UTF-8 그대로 실어 보낸다. isBinaryFile 참조.
 * MCP 미연결 시 오류 응답 반환. 적재 전 사이드바에서 서버를 먼저 연결할 것.
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class EgovDocumentIngestController {

    private final EgovMcpServerRegistry mcpServerRegistry;
    private final EgovIngestProgressRegistry progressRegistry;

    /**
     * 파일을 받아 MCP `ingestDocument` 도구로 적재.
     *
     * jobId 는 선택이다. 브라우저가 미리 만들어 보내면 POST 를 띄우기 전에
     * GET `/ingest/progress/{jobId}` 를 열어 둘 수 있어 진행률을 처음부터 받는다.
     * 보내지 않으면 서버가 UUID 를 만들어 쓴다.
     *
     * @param jobId 영문·숫자·하이픈 64자 이내. 진행률 스트림을 고르는 키, 서버 로그의 추적 ID
     */
    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> ingestDocument(
            @RequestPart("file") FilePart filePart,
            @RequestParam(value = "jobId", required = false) String jobId) {

        // 검증 실패한 값은 로그에 남기지 않는다.
        if (jobId != null && !jobId.isBlank() && !EgovIngestProgressRegistry.isValidJobId(jobId)) {
            return Mono.just(errorResponse("jobId 형식이 올바르지 않습니다. 영문·숫자·하이픈 64자 이내로 보내세요."));
        }
        String resolvedJobId = (jobId == null || jobId.isBlank()) ? UUID.randomUUID().toString() : jobId;

        String filename = filePart.filename();
        if (filename == null || filename.isBlank()) {
            return failed(resolvedJobId, "파일명이 없습니다.");
        }

        String safeFilename = Paths.get(filename).getFileName().toString();
        if (!isSupportedFile(safeFilename)) {
            return failed(resolvedJobId, "PDF, DOCX, HWP, HWPX, 마크다운(.md), 텍스트(.txt) 파일만 지원합니다.");
        }

        McpAsyncClient client = mcpServerRegistry.getClient("rag-server");
        if (client == null) {
            return failed(resolvedJobId,
                    "MCP 서버가 연결되지 않았습니다. 사이드바의 MCP 서버 패널에서 [연결] 버튼을 눌러 연결하세요.");
        }

        String mimeType = getMimeType(safeFilename);

        log.info("[적재] 파일 수신 - jobId: {}, filename: {}", resolvedJobId, safeFilename);

        // 진행률 싱크를 미리 확보한다. 브라우저가 이미 SSE 를 열었다면 그 싱크를 그대로 쓴다.
        progressRegistry.register(resolvedJobId);

        return DataBufferUtils.join(filePart.content(), 50 * 1024 * 1024)
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    String content = isBinaryFile(safeFilename)
                            ? Base64.getEncoder().encodeToString(bytes)
                            : new String(bytes, StandardCharsets.UTF_8);
                    return callMcpIngestTool(client, resolvedJobId, safeFilename, content, mimeType)
                            .map(result -> {
                                Map<String, Object> response = new HashMap<>();
                                response.put("success", true);
                                response.put("jobId", resolvedJobId);
                                response.put("filename", safeFilename);
                                response.put("message", result);
                                return response;
                            });
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("[적재][{}] MCP Tool 실패 - {}", resolvedJobId, e.getMessage());
                    return Mono.just(errorResponse(resolvedJobId, e.getMessage()));
                })
                // 성공·실패·취소 모두 진행률 스트림을 닫는다. 브라우저는 POST 응답으로 판정하므로
                // 이 종료는 EventSource 를 정리하기 위한 것이다.
                .doFinally(signal -> progressRegistry.complete(resolvedJobId));
    }

    /**
     * 적재 진행률을 SSE 로 전송한다.
     *
     * 브라우저는 POST 를 띄우기 전에 이 스트림을 열어야 1단계를 놓치지 않는다.
     *
     * 적재의 성공·실패는 POST 응답이 판정하며, 진행률이 한 건도 오지 않는 정상 경로가 있다
     * 변경된 페이지가 없어 임베딩을 생략한 경우, 서버가 파이프라인 전에 거부한 경우(인증·시그니처·인젝션·덮어쓰기).
     * 
     * 스트림이 끝나면 `EventSource` 는 자동 재접속을 시도한다. 브라우저는 POST
     * 응답을 받은 시점에 `close()` 로 끊어야 한다.
     */
    @GetMapping(value = "/ingest/progress/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<EgovIngestProgressRegistry.ProgressEvent> ingestProgress(@PathVariable String jobId) {
        if (!EgovIngestProgressRegistry.isValidJobId(jobId)) {
            return Flux.empty();
        }
        return progressRegistry.subscribe(jobId);
    }

    // private helpers

    /**
     * egovframe-webflux-mcp-server 의 MCP 도구 `ingestDocument` 를 호출.
     *
     * `jobId` 는 두 군데에 싣는다. 성격이 다른 값이라 한쪽만으로는 부족하다.
     *
     * arguments.jobId : 도구의 인자. 서버가 로그 접두([적재][jobId])와 반환 메시지에 쓴다.
     * _meta.progressToken : MCP 프로토콜 필드. 이것이 없으면 서버는 진행률을 아예 전송하지 않는다. (서버에 진행률을 전송하는 코드가 있더라도)
     * mcp-annotations DefaultMcpAsyncRequestContext.progress() 가 토큰이 비어 있으면 ProgressNotification 을 만들지도 않고
     * Mono.empty() 로 끝낸다(WARN: "Progress notification not supported by the client!").
     *
     * 두 값을 같게 맞추는 이유: callTool() 이 돌려주는 Mono 에는 알림이 섞이지 않고
     * 전역 소비자로 분배되므로(McpAsyncClient:290-298) 어느 호출의 진행률인지 알 방법이
     * progressToken 밖에 없다.
     */
    private Mono<String> callMcpIngestTool(McpAsyncClient client, String jobId,
                                           String filename, String content, String mimeType) {
        Map<String, Object> args = new HashMap<>();
        args.put("jobId", jobId); // 로그 접두 및 반환 메시지용
        args.put("filename", filename);
        args.put("content", content);
        args.put("mimeType", mimeType);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("ingestDocument")
                .arguments(args)
                .progressToken(jobId) // MCP 프로토콜 필드. 이것이 없으면 서버는 진행률을 아예 전송하지 않는다.
                .build();

        return client.callTool(request)
                .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(2))
                        .filter(e -> e instanceof McpTransportSessionNotFoundException
                                || (e.getMessage() != null && e.getMessage().contains("session")))
                        .doBeforeRetry(s -> log.warn("[적재] MCP 세션 만료 감지 - 재연결 후 재시도")))
                .flatMap(result -> {
                    String text = extractText(result);
                    // MCP 서버는 거부·실패를 예외가 아니라 isError=true 플래그로 알린다.
                    // (mcp-annotations AbstractAsyncMcpToolMethodCallback → createAsyncErrorResult,
                    //  mcp-core McpAsyncClient.callTool 은 isError 를 Mono.error 로 바꾸지 않는다.)
                    // 플래그를 보지 않으면 인증 실패·덮어쓰기 차단 등이 성공으로 표시된다.
                    if (Boolean.TRUE.equals(result.isError())) {
                        return Mono.error(new McpToolCallFailedException(stripInvocationPrefix(text)));
                    }
                    return Mono.just(text);
                });
    }

    /** mcp-annotations 가 모든 도구 오류에 붙이는 접두어. */
    private static final String INVOCATION_ERROR_PREFIX = "Error invoking method: ";

    /**
     * 라이브러리가 붙인 접두어를 떼어 사용자에게 보여줄 문구만 남긴다.
     *
     * mcp-annotations 0.9.0 은 도구가 예외로 끝나면 메시지를
     * "Error invoking method: %s".formatted(throwable.getMessage()) 로 감싼다
     * (AbstractAsyncMcpToolMethodCallback) 서버에서는 뗄 수 없다.
     *
     * 그대로 보여주면 정책에 따른 거부(인젝션 탐지 · 덮어쓰기 차단 등)가
     * 내부 결함처럼 읽힌다. 서버는 의도대로 막은 것이므로 실제로는 결함이 아니다.
     *
     * 접두어가 없으면 원문을 그대로 돌려주므로, 라이브러리가 문구를 바꿔도 깨지지 않는다.
     */
    private String stripInvocationPrefix(String text) {
        if (text != null && text.startsWith(INVOCATION_ERROR_PREFIX)) {
            return text.substring(INVOCATION_ERROR_PREFIX.length());
        }
        return text;
    }

    /** CallToolResult 의 첫 TextContent 를 꺼낸다. */
    private String extractText(McpSchema.CallToolResult result) {
        if (result.content() != null && !result.content().isEmpty()) {
            Object first = result.content().get(0);
            if (first instanceof McpSchema.TextContent tc) {
                return tc.text() != null ? tc.text() : "(빈 응답)";
            }
        }
        return "(응답 없음)";
    }

    private String getMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".hwpx")) return "application/hwp+zip";
        if (lower.endsWith(".hwp"))  return "application/x-hwp";
        if (lower.endsWith(".txt"))  return "text/plain";
        return "text/markdown";
    }

    private boolean isSupportedFile(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".docx")
                || lower.endsWith(".hwp") || lower.endsWith(".hwpx")
                || lower.endsWith(".md") || lower.endsWith(".txt");
    }

    /**
     * 바이너리 형식이면 Base64 로 실어야 한다.
     *
     * 판정 기준을 mimeType 이 아니라 확장자로 둔다. mimeType 은 이 컨트롤러가
     * `getMimeType` 으로 만들어 내는 값이라 확장자와 항상 붙어 다니고,
     * 서버도 같은 이유로 확장자를 우선한다(서버 쪽의 isTextMimeType 참조)
     */
    private boolean isBinaryFile(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".docx")
                || lower.endsWith(".hwp") || lower.endsWith(".hwpx");
    }

    /**
     * 실패 응답을 돌려주면서 진행률 스트림도 닫는다.
     *
     * 브라우저가 POST 보다 먼저 SSE 를 열었다면 그 스트림이 열린 채 남는다. 여기서 닫아
     * EventSource 가 바로 정리되게 한다. 싱크가 없으면 아무 일도 하지 않는다.
     */
    private Mono<Map<String, Object>> failed(String jobId, String message) {
        progressRegistry.complete(jobId);
        return Mono.just(errorResponse(jobId, message));
    }

    private Map<String, Object> errorResponse(String message) {
        return errorResponse(null, message);
    }

    private Map<String, Object> errorResponse(String jobId, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        if (jobId != null) {
            response.put("jobId", jobId);
        }
        return response;
    }
}
