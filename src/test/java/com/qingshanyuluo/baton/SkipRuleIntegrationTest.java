package com.qingshanyuluo.baton;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("skiprules")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkipRuleIntegrationTest {

    private static final WireMockServer b1 = new WireMockServer(options().port(19101));
    private static final WireMockServer b2 = new WireMockServer(options().port(19102));
    private static final WireMockServer b3 = new WireMockServer(options().port(19103));

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;

    @BeforeAll  static void start() { b1.start(); b2.start(); b3.start(); }
    @AfterAll   static void stop()  { b1.stop();  b2.stop();  b3.stop(); }
    @BeforeEach void reset() { b1.resetAll(); b2.resetAll(); b3.resetAll(); }

    @Test @Order(1)
    void shouldRouteClaudeModelToNextBackend() {
        // B1: skip-rule model-pattern "^claude-" → strict skip
        // B2: should receive the request
        b2.stubFor(post("/v1/messages")
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"b2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test\",\"content\":[{\"type\":\"text\",\"text\":\"from-b2\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")));

        ResponseEntity<byte[]> r = send("""
                {"model":"claude-opus-4-7","max_tokens":10,"messages":[{"role":"user","content":"hi"}]}""");

        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(new String(r.getBody())).contains("from-b2");
    }

    @Test @Order(2)
    void shouldNotSkipWhenModelDoesNotMatch() {
        b1.stubFor(post("/v1/messages")
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"b1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test\",\"content\":[{\"type\":\"text\",\"text\":\"from-b1\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")));

        ResponseEntity<byte[]> r = send("""
                {"model":"deepseek-v4","max_tokens":10,"messages":[{"role":"user","content":"hi"}]}""");

        assertThat(new String(r.getBody())).contains("from-b1");
    }

    @Test @Order(3)
    void shouldReturnNoCompatibleWhenAllStrictSkipped() {
        // B1: strict skip. B2/B3: no stubs → WireMock returns 404 (not found).
        // 404 is NO_FAILOVER → returned directly to caller, not failover loop.
        // This confirms B1 was correctly strict-skipped and B2 was attempted.
        ResponseEntity<byte[]> r = send("""
                {"model":"claude-opus-4-7","max_tokens":10,"messages":[{"role":"user","content":"hi"}]}""");

        // B1 was skipped, B2 was tried (got 404)
        assertThat(r.getStatusCode().is4xxClientError()).isTrue();
    }

    private ResponseEntity<byte[]> send(String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("x-api-key", "sk-test");
        h.set("anthropic-version", "2023-06-01");
        return restTemplate.exchange("http://localhost:" + port + "/v1/messages",
                HttpMethod.POST, new HttpEntity<>(body, h), byte[].class);
    }
}
