package com.enterprise.openfinance.recurringpayments.infrastructure.external;

import com.enterprise.openfinance.recurringpayments.domain.model.PsuConsent;
import com.enterprise.openfinance.recurringpayments.infrastructure.observability.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Consumer contract with consent-authorization-service GET /api/v1/consents/{id}.
 * The response bodies have the shape of the provider's ConsentServiceView
 * (fintechbankx-openfinance-consent-auth-service, branch
 * claude/openfinance-deployable-ra36dq at c20d4d9: consentId, participantId,
 * customerId, scopes, accountIds, status, expiresAt, usable); scope names are
 * the provider's canonical ones (Consent.SUPPORTED_SCOPES).
 */
class HttpPsuConsentAdapterTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://consent.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpPsuConsentAdapter adapter = new HttpPsuConsentAdapter(builder.build(), () -> "svc-token");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void readsTheProviderViewWithThisServicesTokenAndIgnoresUnknownFields() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "ix-consent");
        server.expect(requestTo("http://consent.test/api/v1/consents/CONS-AUTH-1"))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andExpect(header("X-FAPI-Interaction-ID", "ix-consent"))
                .andRespond(withSuccess("""
                        {"consentId": "CONS-AUTH-1", "participantId": "TPP-001", "customerId": "1",
                         "scopes": ["INITIATEVRP", "READACCOUNTS"], "accountIds": ["ACC-1", "ACC-2"],
                         "status": "AUTHORIZED", "expiresAt": "2026-12-31T23:59:59Z", "usable": true,
                         "addedLater": "ignored"}
                        """, MediaType.APPLICATION_JSON));

        Optional<PsuConsent> consent = adapter.findConsent("CONS-AUTH-1");

        assertThat(consent).contains(new PsuConsent("CONS-AUTH-1", "TPP-001", "1", Set.of("INITIATEVRP", "READACCOUNTS"),
                Set.of("ACC-1", "ACC-2"), Instant.parse("2026-12-31T23:59:59Z"), true));
        server.verify();
    }

    @Test
    void aPendingConsentIsReadAsNotUsableAndAnUnknownOneAsEmpty() {
        server.expect(requestTo("http://consent.test/api/v1/consents/CONS-PENDING"))
                .andRespond(withSuccess("""
                        {"consentId": "CONS-PENDING", "participantId": "TPP-001", "customerId": "CUST-1",
                         "scopes": ["INITIATEVRP"], "accountIds": [], "status": "PENDING",
                         "expiresAt": "2026-12-31T23:59:59Z", "usable": false}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://consent.test/api/v1/consents/CONS-NOPE"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adapter.findConsent("CONS-PENDING")).hasValueSatisfying(c -> assertThat(c.usable()).isFalse());
        assertThat(adapter.findConsent("CONS-NOPE")).isEmpty();
    }

    @Test
    void failsClosedWhenTheConsentServiceRefusesFailsOrAnswersIncompletely() {
        server.expect(requestTo("http://consent.test/api/v1/consents/C-403")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo("http://consent.test/api/v1/consents/C-500"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo("http://consent.test/api/v1/consents/C-PARTIAL"))
                .andRespond(withSuccess("{\"consentId\": \"C-PARTIAL\"}", MediaType.APPLICATION_JSON));

        for (String id : new String[] {"C-403", "C-500", "C-PARTIAL"}) {
            assertThatThrownBy(() -> adapter.findConsent(id)).isInstanceOf(ConsentServiceUnavailableException.class);
        }
    }

    @Test
    void inMemoryDemoConsentsForLocalRuns() {
        InMemoryPsuConsentAdapter demo = new InMemoryPsuConsentAdapter();
        assertThat(demo.findConsent("CONS-DEMO-VRP-1")).hasValueSatisfying(c -> {
            assertThat(c.usable()).isTrue();
            assertThat(c.accountIds()).containsExactly("ACC-AED-ACTIVE");
        });
        assertThat(demo.findConsent("CONS-DEMO-PENDING")).hasValueSatisfying(c -> assertThat(c.usable()).isFalse());
        assertThat(demo.findConsent("CONS-UNKNOWN")).isEmpty();
    }
}
