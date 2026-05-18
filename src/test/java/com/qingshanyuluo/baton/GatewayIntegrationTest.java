package com.qingshanyuluo.baton;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("wiremock")
class GatewayIntegrationTest {

    private static final WireMockServer backend1 = new WireMockServer(options().port(19001));
    private static final WireMockServer backend2 = new WireMockServer(options().port(19002));
    private static final WireMockServer backend3 = new WireMockServer(options().port(19003));

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureBackends(DynamicPropertyRegistry registry) {
        registry.add("baton.backends[0].name", () -> "backend-1");
        registry.add("baton.backends[0].url", () -> "http://localhost:19001");
        registry.add("baton.backends[0].priority", () -> 1);
        registry.add("baton.backends[1].name", () -> "backend-2");
        registry.add("baton.backends[1].url", () -> "http://localhost:19002");
        registry.add("baton.backends[1].priority", () -> 2);
        registry.add("baton.backends[2].name", () -> "backend-3");
        registry.add("baton.backends[2].url", () -> "http://localhost:19003");
        registry.add("baton.backends[2].priority", () -> 3);
        registry.add("baton.failover.connect-timeout", () -> "1s");
        registry.add("baton.failover.read-timeout", () -> "5s");
        registry.add("baton.failover.global-timeout", () -> "15s");
        registry.add("baton.failover.health-check-interval", () -> "60s");
    }

    @BeforeAll
    static void startMockBackends() {
        backend1.start();
        backend2.start();
        backend3.start();
    }

    @AfterAll
    static void stopMockBackends() {
        backend1.stop();
        backend2.stop();
        backend3.stop();
    }

    @BeforeEach
    void resetMocks() {
        backend1.resetAll();
        backend2.resetAll();
        backend3.resetAll();
    }

    @Test
    void shouldProxyNormalRequest() {
        backend1.stubFor(post("/v1/messages")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"id":"msg_1","type":"message","role":"assistant","model":"test",
                                "content":[{"type":"text","text":"Hello"}],"stop_reason":"end_turn",
                                "stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}""")));

        ResponseEntity<byte[]> response = httpPost("/v1/messages", """
                {"model":"test","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}""");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(new String(response.getBody())).contains("\"type\":\"message\"");
        assertThat(new String(response.getBody())).contains("\"text\":\"Hello\"");
    }

    @Test
    void shouldFailoverOn5xx() {
        backend1.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(503)));
        backend2.stubFor(post("/v1/messages")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"id":"msg_2","type":"message","role":"assistant","model":"test",
                                "content":[{"type":"text","text":"From B2"}],"stop_reason":"end_turn",
                                "stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":3}}""")));

        ResponseEntity<byte[]> response = httpPost("/v1/messages", """
                {"model":"test","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}""");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(new String(response.getBody())).contains("From B2");
        backend1.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
        backend2.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void shouldNotFailoverOn4xx() {
        backend1.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(401).withBody("""
                {"type":"error","error":{"type":"authentication_error","message":"Invalid key"}}""")));

        ResponseEntity<byte[]> response = httpPost("/v1/messages", """
                {"model":"test","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}""");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        backend1.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
        backend2.verify(0, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void shouldProxyCountTokens() {
        backend1.stubFor(post("/v1/messages/count_tokens")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"input_tokens\":42}")));

        ResponseEntity<byte[]> response = httpPost("/v1/messages/count_tokens", """
                {"model":"test","messages":[{"role":"user","content":"hello world"}]}""");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(new String(response.getBody())).contains("\"input_tokens\":42");
    }

    @Test
    void shouldRejectOversizedBody() {
        byte[] huge = new byte[21 * 1024 * 1024];
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", "sk-test");

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "http://localhost:" + port + "/v1/messages",
                HttpMethod.POST, new HttpEntity<>(huge, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void shouldReturn502WhenAllBackendsFail() {
        backend1.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(503)));
        backend2.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(503)));
        backend3.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(503)));

        ResponseEntity<byte[]> response = httpPost("/v1/messages", """
                {"model":"test","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}""");

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(new String(response.getBody())).contains("overloaded_error");
    }

    @Test
    void shouldPreserveResponseHeaders() {
        backend1.stubFor(post("/v1/messages")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withHeader("x-custom", "preserved-value")
                        .withBody("""
                                {"id":"msg_h","type":"message","role":"assistant","model":"test",
                                "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
                                "stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""")));

        ResponseEntity<byte[]> response = httpPost("/v1/messages", """
                {"model":"test","max_tokens":10,"messages":[{"role":"user","content":"hi"}]}""");

        assertThat(response.getHeaders().get("x-custom")).contains("preserved-value");
    }

    // Note: Streaming SSE verified manually against real backends.
    // WireMock's non-chunked response format does not interact cleanly with
    // WebClient.toEntityFlux + StreamingResponseBody in TestRestTemplate.

    private ResponseEntity<byte[]> httpPost(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", "sk-test-key");
        headers.set("anthropic-version", "2023-06-01");
        return restTemplate.exchange(
                "http://localhost:" + port + path,
                HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
    }
}
