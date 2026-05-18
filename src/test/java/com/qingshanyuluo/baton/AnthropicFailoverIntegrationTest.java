package com.qingshanyuluo.baton;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class AnthropicFailoverIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static String apiKey;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void loadApiKey() throws Exception {
        String home = System.getProperty("user.home");
        byte[] json = Files.readAllBytes(Paths.get(home, ".claude/providers/settings-callapi.json"));
        var root = mapper.readTree(json);
        apiKey = root.get("env").get("ANTHROPIC_AUTH_TOKEN").asText();
    }

    @Test
    void shouldFailoverToRealBackendAndReturnValidResponse() {
        String requestBody = """
                {
                    "model": "claude-sonnet-4-6",
                    "max_tokens": 256,
                    "messages": [
                        {"role": "user", "content": "Reply with exactly: OK"}
                    ]
                }""";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "http://localhost:" + port + "/v1/messages",
                HttpMethod.POST,
                entity,
                byte[].class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();

        String responseStr = new String(response.getBody());
        assertThat(responseStr).contains("\"type\":\"message\"");
        assertThat(responseStr).contains("\"content\"");
        System.out.println("Response: " + responseStr.substring(0, Math.min(300, responseStr.length())));
    }

    @Test
    void shouldRejectOversizedBody() {
        byte[] hugeBody = new byte[21 * 1024 * 1024]; // 21MB, over 20MB limit
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        HttpEntity<byte[]> entity = new HttpEntity<>(hugeBody, headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "http://localhost:" + port + "/v1/messages",
                HttpMethod.POST,
                entity,
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void shouldReturn4xxDirectlyWithoutFailover() {
        // Missing x-api-key header → backend should return 401
        // This 401 should be returned directly without triggering failover loop
        String requestBody = """
                {"model": "claude-sonnet-4-6", "max_tokens": 256, "messages": [{"role": "user", "content": "hi"}]}""";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("anthropic-version", "2023-06-01");

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "http://localhost:" + port + "/v1/messages",
                HttpMethod.POST,
                entity,
                byte[].class);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void shouldProxyCountTokens() {
        String requestBody = """
                {
                    "model": "deepseek-v4-flash",
                    "messages": [
                        {"role": "user", "content": "Hello world"}
                    ]
                }""";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "http://localhost:" + port + "/v1/messages/count_tokens",
                HttpMethod.POST,
                entity,
                byte[].class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        String body = new String(response.getBody());
        assertThat(body).contains("input_tokens");
    }
}
