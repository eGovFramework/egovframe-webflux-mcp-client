package com.example.client.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.example.client.dto.ServerStatusDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.annotation.spring.ClientMcpAsyncHandlersRegistry;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties.Parameters;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties.ConnectionParameters;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * HTTP(Streamable) 및 stdio MCP 서버를 통합 관리하는 레지스트리.
 *
 * 서버별 상태(DISCONNECTED / CONNECTED / FAILED)를 따로 추적하고,
 * connect/disconnect/restrictedAllowed 를 REST API(/api/mcp/servers/**)로 UI 에 노출한다.
 * 빈 생성과 기동 시 1회 연결 시도는 EgovMcpOptionalConfig 가 한다.
 * 자동 구성을 둘만 제외하고 이 클래스로 대신하는 이유는 해당 클래스 주석 참고.
 *
 * 전송 계층·프로퍼티 바인딩·어노테이션 스캔은 자동 구성을 그대로 쓴다.
 * 대신한 것은 둘이다.
 *
 *   McpClientAutoConfiguration        기동 시 initialize().block() 으로 연결을 기동 성공
 *                                     조건으로 만든다
 *     → connect(name) / disconnect(name) 가 대신한다. 실패해도 예외를 던지지 않고
 *       상태만 FAILED 로 기록하므로, 나중에 다시 붙을 수 있다.
 *
 *   McpToolCallbackAutoConfiguration  AsyncMcpToolCallbackProvider 를 싱글톤으로 한 번만
 *                                     만들어 생성 시점 목록으로 박제한다
 *     → getToolCallbacks() 가 대신한다. 호출마다 지금 연결된 클라이언트로 다시 빌드하므로
 *       재연결 결과가 바로 반영된다. 이게 없으면 다시 연결해도 도구는 옛 목록이다.
 *
 * 연결 자체를 만드는 코드는 제외한 자동 구성에서 옮겨온 것이다.
 * 무엇을 하는지는 같고 '언제 · 몇 번 · 실패하면 어떻게' 만 바꿨다.
 *
 * 이 클래스를 고칠 때 유의 사항.
 *
 * 퍼블릭 메서드가 대부분 synchronized 다. 상태 맵을 UI 스레드(REST)와 채팅
 * 요청 스레드가 함께 건드리기 때문이다. 블로킹 호출(initialize().block(), listTools().block()) 이 그 안에 있으므로
 * 이벤트 루프에서 부르면 안 된다. boundedElastic 등으로 빼서 호출할 것.
 */
@Slf4j
public class EgovMcpServerRegistry {

    public enum Status { DISCONNECTED, CONNECTED, FAILED }

    // 내부 모델

    private static class ServerConfig {
        final String type;
        final ConnectionParameters httpParams;
        final Parameters stdioParams;

        ServerConfig(ConnectionParameters httpParams) {
            this.type = "http";
            this.httpParams = httpParams;
            this.stdioParams = null;
        }

        ServerConfig(Parameters stdioParams) {
            this.type = "stdio";
            this.httpParams = null;
            this.stdioParams = stdioParams;
        }
    }

    /** 테스트에서 직접 다루므로 package-private 이다. */
    static class ServerRuntime {
        volatile Status status = Status.DISCONNECTED;
        McpAsyncClient client;
        List<McpSchema.Tool> tools = Collections.emptyList();
        boolean restrictedAllowed = false;
        String lastError;
        boolean authFailed;
        /** stdio 서버가 마지막으로 stderr 에 남긴 줄. 실패 사유 표시에 쓰고 비운다. */
        volatile String lastStdErr;
    }

    // 필드

    private final Map<String, ServerConfig> configs = new LinkedHashMap<>();
    private final Map<String, ServerRuntime> runtimes = new LinkedHashMap<>();

    private final ObjectProvider<WebClient.Builder> webClientBuilderProvider;
    private final ObjectMapper objectMapper;
    private final ClientMcpAsyncHandlersRegistry handlerRegistry;
    private final long requestTimeoutMs;
    private final String clientId;
    private final String mcpApiKey;

    public EgovMcpServerRegistry(
            McpStreamableHttpClientProperties httpProperties,
            McpStdioClientProperties stdioProperties,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectMapper objectMapper,
            ClientMcpAsyncHandlersRegistry handlerRegistry,
            long requestTimeoutMs,
            String clientId,
            String mcpApiKey) {

        this.webClientBuilderProvider = webClientBuilderProvider;
        this.objectMapper = objectMapper;
        this.handlerRegistry = handlerRegistry;
        this.requestTimeoutMs = requestTimeoutMs;
        this.clientId = clientId;
        this.mcpApiKey = mcpApiKey;

        if (httpProperties != null && httpProperties.getConnections() != null) {
            httpProperties.getConnections().forEach((name, params) -> {
                configs.put(name, new ServerConfig(params));
                runtimes.put(name, new ServerRuntime());
            });
        }

        if (stdioProperties != null && stdioProperties.getConnections() != null) {
            stdioProperties.getConnections().forEach((name, def) -> {
                configs.put(name, new ServerConfig(def));
                runtimes.put(name, new ServerRuntime());
            });
        }
    }

    // 퍼블릭 API

    /** 기동 시 모든 서버 연결 시도. 실패해도 예외를 전파하지 않는다. */
    public void tryConnectAll() {
        configs.keySet().forEach(name -> {
            try {
                connect(name);
            } catch (Exception e) {
                log.warn("[MCP] '{}' 초기 연결 실패: {}", name, e.getMessage());
            }
        });
    }

    /**
     * 서버 연결. 이미 CONNECTED 상태이면 현재 상태를 그대로 반환한다.
     * 블로킹 호출이므로 boundedElastic 스케줄러에서 호출할 것.
     */
    public synchronized ServerStatusDto connect(String name) {
        ServerConfig config = configs.get(name);
        if (config == null) throw new IllegalArgumentException("알 수 없는 서버: " + name);

        ServerRuntime runtime = runtimes.get(name);
        if (runtime.status == Status.CONNECTED) {
            log.info("[MCP] '{}' 이미 연결됨", name);
            return toDto(name, config, runtime);
        }

        runtime.lastError = null;
        runtime.authFailed = false;
        // 지난 시도의 stderr 가 이번 실패 사유로 섞이지 않게 비운다.
        runtime.lastStdErr = null;

        // initialize() 실패 시에도 클라이언트를 close() 할 수 있도록 try 블록 밖에서 선언.
        // runtime.client는 성공 시에만 저장되므로 cleanupRuntime()만으로는 누수 방지 불가.
        McpAsyncClient client = null;
        try {
            client = "http".equals(config.type)
                    ? createHttpClient(name, config)
                    : createStdioClient(name, config, runtime);

            client.initialize().block(Duration.ofSeconds(15));

            runtime.client = client;
            runtime.tools = fetchTools(client);
            runtime.status = Status.CONNECTED;
            List<String> names = runtime.tools.stream().map(McpSchema.Tool::name).toList();
            log.info("[MCP] '{}' 연결 성공 - 도구 {}개: {}", name, names.size(), names);

        } catch (Exception e) {
            runtime.status = Status.FAILED;
            recordFailure(runtime, e, null);
            // initialize() 실패 시 지역 변수 client가 runtime.client에 저장되기 전에 예외가 발생.
            // 이 경우 cleanupRuntime()은 runtime.client == null 이라 close()를 호출하지 못하므로
            // 여기서 직접 닫아 Netty 연결 시도가 백그라운드에서 지속되는 것을 방지한다.
            if (client != null) {
                try { client.close(); } catch (Exception ignored) {}
            }
            cleanupRuntime(runtime);
            log.warn("[MCP] '{}' 연결 실패: {}", name, e.getMessage());
        }

        return toDto(name, config, runtime);
    }

    /** 서버 연결 해제. */
    public synchronized void disconnect(String name) {
        ServerConfig config = configs.get(name);
        if (config == null) throw new IllegalArgumentException("알 수 없는 서버: " + name);

        ServerRuntime runtime = runtimes.get(name);
        cleanupRuntime(runtime);
        runtime.status = Status.DISCONNECTED;
        runtime.lastError = null;
        runtime.authFailed = false;
        runtime.lastStdErr = null;
        runtime.tools = Collections.emptyList();
        runtime.restrictedAllowed = false;
        log.info("[MCP] '{}' 연결 해제", name);
    }

    /** 전체 서버 상태 목록. */
    public synchronized List<ServerStatusDto> getServerStatuses() {
        List<ServerStatusDto> result = new ArrayList<>();
        configs.forEach((name, config) -> result.add(toDto(name, config, runtimes.get(name))));
        return result;
    }

    /**
     * 연결된 모든 서버의 도구 콜백 집합.
     *
     * 각 클라이언트에 listTools() 를 먼저 호출해 응답하는지 확인한다.
     * 핑 실패 시 FAILED 처리 후 제외, 성공한 클라이언트만 콜백 제공.
     * 모두 실패해도 예외를 던지지 않는다.
     *
     * 여기서 반환하는 콜백은 전부 `wrapWithSafetyGuard`로 감싸므로,
     * LLM 이 호출하는 경로에는 [위험 작업 허용] 토글에 따른 차단 검사가 걸린다.
     * 사용자가 채팅에서 "ingestDocument 로 이 문서 적재해줘" 처럼 위험 도구를 직접 지목해도 그 검사를 거친다.
     * 반면 앱이 `getClient(String)` 으로 직접 호출하는 경로는 그 검사를 거치지 않는다.
     */
    public synchronized ToolCallback[] getToolCallbacks() {
        List<McpAsyncClient> live = new ArrayList<>();
        for (Entry<String, ServerRuntime> entry : runtimes.entrySet()) {
            String name = entry.getKey();
            ServerRuntime runtime = entry.getValue();
            if (runtime.status != Status.CONNECTED || runtime.client == null) continue;
            try {
                runtime.client.listTools(null).block(Duration.ofSeconds(5));
                live.add(runtime.client);
            } catch (Exception e) {
                log.warn("[MCP] '{}' 핑 실패 - FAILED 처리: {}", name, e.getMessage());
                runtime.status = Status.FAILED;
                recordFailure(runtime, e, "서버 응답 없음: ");
                cleanupRuntime(runtime);
            }
        }
        if (live.isEmpty()) return new ToolCallback[0];

        // 응답한 클라이언트별로 콜백을 얻어 위험 작업 차단 검사로 감싼다
        List<ToolCallback> all = new ArrayList<>();
        for (Entry<String, ServerRuntime> entry : runtimes.entrySet()) {
            ServerRuntime rt = entry.getValue();
            if (!live.contains(rt.client)) continue;
            ToolCallback[] cbs = AsyncMcpToolCallbackProvider.builder()
                    .mcpClients(List.of(rt.client))
                    .build()
                    .getToolCallbacks();
            for (ToolCallback cb : cbs) {
                all.add(wrapWithSafetyGuard(cb, rt));
            }
        }
        return all.toArray(new ToolCallback[0]);
    }

    /** 제한 작업 허용 여부 토글. */
    public synchronized ServerStatusDto setRestrictedAllowed(String name, boolean allowed) {
        ServerConfig config = configs.get(name);
        if (config == null) throw new IllegalArgumentException("알 수 없는 서버: " + name);
        ServerRuntime runtime = runtimes.get(name);
        runtime.restrictedAllowed = allowed;
        log.info("[MCP] '{}' 위험 작업 {}", name, allowed ? "허용" : "차단");
        return toDto(name, config, runtime);
    }

    /**
     * 특정 이름의 서버 클라이언트 반환 (MCP 도구 직접 호출용, e.g. 문서 적재).
     * 미연결이면 null 반환.
     *
     * 이 경로에는 [위험 작업 허용] 차단 검사가 걸리지 않는다. 그 검사는
     * `getToolCallbacks()` 가 LLM 에게 넘기는 콜백에만 적용된다.
     *
     * 차단 검사가 막으려는 대상은 '위험한 도구' 가 아니라 LLM 의 자율 행동이다.
     * 이 메서드를 쓰는 곳은 `/ingest` 화면처럼 사용자가 파일을 고르고 버튼을 누른
     * 명시적 조작이다.
     *
     *   채팅에서 LLM 이 ingestDocument 호출   → 검사 적용   (체크 없으면 차단)
     *   /ingest 화면에서 업로드                → 검사 미적용 (항상 동작)
     *   채팅에서 LLM 이 write_file 호출        → 검사 적용   (체크 없으면 차단)
     *
     * 사용자가 채팅으로 "ingestDocument 로 적재해줘" 라고 지시하면 그것은 LLM 호출이므로
     * 그 검사에 걸린다.
     */
    public synchronized McpAsyncClient getClient(String name) {
        ServerRuntime runtime = runtimes.get(name);
        if (runtime == null || runtime.status != Status.CONNECTED) return null;
        return runtime.client;
    }

    /** 특정 서버의 현재 상태 반환. 존재하지 않는 이름이면 null. */
    public synchronized ServerStatusDto getServerStatus(String name) {
        ServerConfig config = configs.get(name);
        if (config == null) return null;
        return toDto(name, config, runtimes.get(name));
    }

    /** 연결된 서버가 하나 이상인지 확인. */
    public synchronized boolean hasAnyConnected() {
        return runtimes.values().stream().anyMatch(runtime -> runtime.status == Status.CONNECTED);
    }

    /** yml에 등록된 서버가 하나 이상인지 확인 (연결 여부 무관). */
    public synchronized boolean hasAnyServer() {
        return !configs.isEmpty();
    }

    /**
     * 모든 서버 연결과 stdio 자식 프로세스 정리.
     *
     * 컨텍스트가 닫힐 때 Spring 이 부른다. EgovMcpOptionalConfig 의
     * @Bean(destroyMethod = "shutdown"). 
     * 중복 호출에 안전하다(cleanupRuntime 이 client 를 null 로 비운다).
     */
    public synchronized void shutdown() {
        runtimes.forEach((name, runtime) -> cleanupRuntime(runtime));
        log.info("[MCP] 모든 서버 연결 종료 완료");
    }

    // private helpers

    private static final int HTTP_CONNECT_TIMEOUT_MS = 5_000;

    /**
     * 서버가 인증 거부에 쓰는 JSON-RPC 오류 코드.
     *
     * egovframe-webflux-mcp-server 의 `EgovApiSecurityFilter.ERROR_CODE_UNAUTHORIZED`
     * 와 같은 값이어야 한다. 바꾸려면 양쪽을 함께 고칠 것.
     * 문구 대신 코드로 판별하는 이유는, 메시지를 다듬을 때마다 화면 분기가 깨지지
     * 않게 하려는 것이다.
     */
    private static final int ERROR_CODE_UNAUTHORIZED = -32001;

    private McpAsyncClient createHttpClient(String name, ServerConfig config) {
        ConnectionParameters params = config.httpParams;
        String url = params.url();
        String endpoint = params.endpoint() != null ? params.endpoint() : "/mcp";

        // TCP 연결 타임아웃을 5초로 제한.
        // 원격지가 죽어있을 때 OS의 TCP 타임아웃(20-30초)까지 기다리면
        // block(15s)가 먼저 풀려도 Netty 연결 시도가 백그라운드에서 계속 돌다가
        // LifecycleInitializer WARN이 늦게 발생한다.
        // 5초 이내에 TCP 핸드셰이크가 완료되지 않으면 바로 실패 처리한다.
        HttpClient nettyClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, HTTP_CONNECT_TIMEOUT_MS);

        WebClient.Builder builder = webClientBuilderProvider
                .getIfAvailable(WebClient::builder)
                .clone()
                .baseUrl(url)
                .clientConnector(new ReactorClientHttpConnector(nettyClient));

        // 연결 단위 기본 헤더다. initialize 를 포함한 모든 요청에 붙는다.
        // 서버(egovframe-webflux-mcp-server)는 MCP 엔드포인트 전체에 이 헤더를 요구하므로,
        // 값이 비어 있으면 서버 연결 자체가 403 으로 실패하고 검색도 되지 않는다.
        // 401 이 아닌 이유는 서버 쪽 EgovApiSecurityFilter 주석 참고(401 은 MCP 규격에서
        // OAuth 흐름을 시작하라는 신호라 규격을 따르는 클라이언트가 엉뚱하게 해석한다).
        if (mcpApiKey != null && !mcpApiKey.isBlank()) {
            builder = builder.defaultHeader("X-MCP-API-Key", mcpApiKey);
        } else {
            log.warn("[MCP] '{}' 인증 키 미설정 - X-MCP-API-Key 헤더 없이 연결을 시도합니다. "
                    + "키를 요구하는 서버에서는 403 으로 연결이 실패합니다. "
                    + "환경변수 MCP_API_KEY 또는 app.mcp-api-key 를 설정하세요.", name);
        }

        var transport = WebClientStreamableHttpTransport.builder(builder)
                .endpoint(endpoint)
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .build();

        return wireAndBuild(name, McpClient.async(transport));
    }

    /**
     * stdio 서버 클라이언트 생성.
     *
     * StdioClientTransport가 프로세스 생명주기를 내부에서 관리하므로
     * ServerRuntime.process는 사용하지 않는다.
     * 프로세스 종료는 McpAsyncClient.close() → transport.closeGracefully()로 처리된다.
     *
     * Windows에서 npx 같은 .cmd 파일 실행 시 command를 "cmd", args 첫 번째를 "/c"로 지정할 것.
     */
    private McpAsyncClient createStdioClient(String name, ServerConfig config,
                                              ServerRuntime runtime) {
        // 변환은 Spring AI 가 해 준다. command/args/env 를 직접 만들던 코드를 대체한 것.
        ServerParameters params = config.stdioParams.toServerParameters();

        var transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(objectMapper));
        // 서버가 응답하지 않는 경우 stderr 로 확인할 수 있다.
        transport.setStdErrorHandler(line -> {
            log.warn("[MCP-stdio][{}] stderr: {}", name, line);
            runtime.lastStdErr = line;
        });

        return wireAndBuild(name, McpClient.async(transport));
    }

    private McpAsyncClient wireAndBuild(String name, McpClient.AsyncSpec spec) {
        spec = spec
                .clientInfo(new McpSchema.Implementation(clientId, "1.0.0"))
                .requestTimeout(Duration.ofMillis(requestTimeoutMs));

        if (handlerRegistry != null) {
            // 연결별 capability. 핸들러 애너테이션의 clients 값에 이 이름이 있으면 해당
            // capability 가 켜지고, 없으면 EMPTY_CAPABILITIES(네 필드 전부 null)가 온다.
            // getOrDefault 라서 null 은 돌아오지 않는다. 그래서 아래 람다를 모든 연결에
            // 붙여도 안전하다. 선언하지 않은 서버는 요청하지 않고, 규격을 무시하고 요청하면
            // 레지스트리가 METHOD_NOT_FOUND 로 거절한다.
            spec = spec.capabilities(handlerRegistry.getCapabilities(name));
            spec = spec
                    .sampling(req -> handlerRegistry.handleSampling(name, req))
                    .progressConsumer(progress -> handlerRegistry.handleProgress(name, progress));
        }

        return spec.build();
    }

    private List<McpSchema.Tool> fetchTools(McpAsyncClient client) {
        try {
            McpSchema.ListToolsResult result = client.listTools(null).block(Duration.ofSeconds(5));
            if (result == null || result.tools() == null) return Collections.emptyList();
            return result.tools();
        } catch (Exception e) {
            log.warn("[MCP] 도구 목록 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 제한 작업(데이터를 바꾸거나 외부 시스템에 영향을 주는) 도구인지 판단.
     * 1순위: ToolAnnotations로 판단. readOnlyHint=true(통과), destructiveHint=true 또는 openWorldHint=true(차단)
     * 2순위: 도구 이름 판단 (annotations 미지원 서버 fallback)
     */
    private boolean isRestrictedOperation(McpSchema.Tool tool) {
        if (tool == null) return false;
        McpSchema.ToolAnnotations ann = tool.annotations();
        if (ann != null) {
            if (Boolean.TRUE.equals(ann.readOnlyHint())) return false;
            if (Boolean.TRUE.equals(ann.destructiveHint())) return true;
            if (Boolean.TRUE.equals(ann.openWorldHint())) return true;
        }
        String toolName = tool.name().toLowerCase();
        return // CRUD / 파일시스템
               toolName.contains("write")  || toolName.contains("create") || toolName.contains("delete")
            || toolName.contains("update") || toolName.contains("edit")   || toolName.contains("move")
            || toolName.contains("rename") || toolName.contains("append") || toolName.contains("remove")
            || toolName.contains("overwrite")
            // 코드·셸 실행
            || toolName.contains("execute") || toolName.contains("exec") || toolName.contains("eval")
            || toolName.contains("shell")   || toolName.contains("bash") || toolName.contains("run")
            // 외부 통신·배포
            || toolName.contains("send")   || toolName.contains("deploy") || toolName.contains("ingest")
            // DB 내용을 바꾸거나 지우는 작업
            || toolName.contains("insert") || toolName.contains("drop")
            || toolName.contains("truncate") || toolName.contains("purge");
    }

    /**
     * 위험 작업으로 분류된 도구를 [위험 작업 허용] 토글 뒤로 감싼다.
     *
     * 이 차단 검사는 `getToolCallbacks()` 가 LLM 에게 넘기는 콜백에만 걸린다.
     * 앱이 `getClient(String)` 으로 직접 호출하는 경로에는 걸리지 않는다.
     *
     * 차단 메시지는 도구 호출 결과로 LLM 에게 전달된다. 따라서 모델이 이를 읽고
     * 다른 도구 조합으로 우회를 시도할 수 있다(예를 들어 파일시스템 서버에는 create_directory,
     * edit_file, move_file 등 우회 수단이 많다). 그래서 메시지에 재시도·우회를 하지 말라는 지시를 함께 담는다.
     */
    private ToolCallback wrapWithSafetyGuard(ToolCallback cb, ServerRuntime runtime) {
        McpSchema.Tool mcpTool = runtime.tools.stream()
                .filter(t -> t.name().equals(cb.getToolDefinition().name()))
                .findFirst().orElse(null);
        if (!isRestrictedOperation(mcpTool)) return cb;

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return cb.getToolDefinition();
            }
            @Override
            public String call(String toolInput) {
                if (!runtime.restrictedAllowed) {
                    return "이 도구는 위험 작업으로 분류되어 차단되었습니다. "
                            + "사용자가 직접 허용 설정을 켜야 하며, "
                            + "당신은 이 작업을 다시 시도하거나 다른 방법으로 우회하지 마세요.";
                }
                return cb.call(toolInput);
            }
        };
    }

    private void cleanupRuntime(ServerRuntime runtime) {
        if (runtime.client != null) {
            try { runtime.client.close(); } catch (Exception ignored) {}
            runtime.client = null;
        }
    }

    /**
     * 실패 사유를 런타임에 기록한다. 인증 거부인지 아닌지를 함께 가린다.
     *
     * 체인에 우리 서버가 보낸 McpError(코드 ERROR_CODE_UNAUTHORIZED)가
     * 있으면 인증 거부이고, 그 메시지는 '서버에 키 미등록 / 헤더 없음 / 값 불일치' 중
     * 어느 쪽인지까지 들어 있어 그대로 화면에 쓴다. 없으면 체인 최하위 메시지를 쓴다.
     *
     * @param prefix 메시지 앞에 붙일 문구. 없으면 null.
     */
    // package-private: 사유 조합 규칙을 테스트가 직접 검증한다.
    void recordFailure(ServerRuntime runtime, Throwable error, String prefix) {
        String detail = null;
        for (Throwable c = error; c != null; c = c.getCause()) {
            if (c instanceof McpError mcpError
                    && mcpError.getJsonRpcError() != null
                    && mcpError.getJsonRpcError().code() == ERROR_CODE_UNAUTHORIZED) {
                runtime.authFailed = true;
                runtime.lastError = mcpError.getJsonRpcError().message();
                return;
            }
            if (c.getMessage() != null && !c.getMessage().isBlank()) {
                detail = c.getMessage();
            }
        }
        runtime.authFailed = false;
        String message = detail != null ? detail : String.valueOf(error);

        // stdio 서버의 stderr 가 있으면 추가로 출력한다.
        String stdErr = runtime.lastStdErr;
        runtime.lastStdErr = null;
        if (stdErr != null && !stdErr.isBlank()) {
            message = stdErr.strip() + " (" + message + ")";
        }

        runtime.lastError = prefix != null ? prefix + message : message;
    }

    private ServerStatusDto toDto(String name, ServerConfig config, ServerRuntime runtime) {
        List<String> toolNames = runtime.tools.stream().map(McpSchema.Tool::name).toList();
        return new ServerStatusDto(
                name, config.type, runtime.status.name(),
                runtime.lastError, runtime.authFailed, toolNames, runtime.restrictedAllowed);
    }
}
