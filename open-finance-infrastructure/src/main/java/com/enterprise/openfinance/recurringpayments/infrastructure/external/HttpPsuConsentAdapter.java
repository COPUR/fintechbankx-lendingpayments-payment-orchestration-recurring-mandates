package com.enterprise.openfinance.recurringpayments.infrastructure.external;

import com.enterprise.openfinance.recurringpayments.domain.model.PsuConsent;
import com.enterprise.openfinance.recurringpayments.domain.port.out.PsuConsentPort;
import com.enterprise.openfinance.recurringpayments.infrastructure.observability.CorrelationIdFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Anti-corruption adapter to consent-authorization-service
 * (fintechbankx-openfinance-consent-auth-service), internal read
 * GET /api/v1/consents/{consentId}; never the /open-finance/v1 TPP path.
 * Calls carry this service's own client-credentials token. The response's
 * {@code usable} flag is authoritative. 404 means no consent; any other
 * failure, or a response without the fields read here, fails closed (503).
 */
public class HttpPsuConsentAdapter implements PsuConsentPort {

    static final String CONSENT_PATH = "/api/v1/consents/{consentId}";

    private final RestClient client;
    private final Supplier<String> serviceToken;

    public HttpPsuConsentAdapter(RestClient client, Supplier<String> serviceToken) {
        this.client = client;
        this.serviceToken = serviceToken;
    }

    @Override
    public Optional<PsuConsent> findConsent(String consentId) {
        ConsentView view;
        try {
            view = client.get()
                    .uri(CONSENT_PATH, consentId)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + serviceToken.get())
                    .header("X-FAPI-Interaction-ID", interactionId())
                    .retrieve()
                    .body(ConsentView.class);
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw new ConsentServiceUnavailableException("Consent service refused the lookup: "
                    + exception.getStatusCode().value(), exception);
        } catch (RestClientException | IllegalStateException exception) {
            throw new ConsentServiceUnavailableException("Consent service unavailable", exception);
        }
        if (view == null || view.consentId() == null || view.participantId() == null || view.customerId() == null
                || view.expiresAt() == null || view.usable() == null) {
            throw new ConsentServiceUnavailableException("Consent service returned an incomplete consent", null);
        }
        return Optional.of(new PsuConsent(view.consentId(), view.participantId(), view.customerId(),
                view.scopes() == null ? Set.of() : Set.copyOf(view.scopes()),
                view.accountIds() == null ? Set.of() : Set.copyOf(view.accountIds()),
                view.expiresAt(), view.usable()));
    }

    private static String interactionId() {
        String fromRequest = MDC.get(CorrelationIdFilter.MDC_KEY);
        return fromRequest != null ? fromRequest : UUID.randomUUID().toString();
    }

    /**
     * The provider's minimal internal view {consentId, participantId, customerId,
     * scopes, accountIds, status, expiresAt, usable}; new fields are ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConsentView(String consentId, String participantId, String customerId, List<String> scopes,
                       List<String> accountIds, String status, Instant expiresAt, Boolean usable) {
    }
}
