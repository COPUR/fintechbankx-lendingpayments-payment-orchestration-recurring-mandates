package com.enterprise.openfinance.recurringpayments;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Over a real Tomcat (MockMvc never performs the ERROR dispatch): a status
 * produced by response.sendError, such as the firewall's 400 for a
 * malformed path, must reach the caller as that status, not as the 401/403
 * the security rules would give the /error dispatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mandates.accounts.adapter=in-memory",
        "mandates.outbox.relay.enabled=false",
        "management.tracing.enabled=false",
        "management.server.port=0"
})
class ErrorDispatchIT {

    @LocalServerPort int port;
    @MockBean KafkaTemplate<String, String> kafka;
    @MockBean JwtDecoder jwtDecoder;

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Test
    void aFirewallRejectionIsA400NotA401Or403() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/open-finance/v1/vrp/payments/a%3Bb")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
    }
}
