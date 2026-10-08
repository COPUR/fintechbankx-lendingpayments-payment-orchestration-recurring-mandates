package com.enterprise.openfinance.recurringpayments.infrastructure.external;

import com.enterprise.openfinance.recurringpayments.domain.model.DebtorAccount;
import com.enterprise.openfinance.recurringpayments.domain.port.out.DebtorAccountPort;
import com.enterprise.openfinance.recurringpayments.infrastructure.observability.CorrelationIdFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Anti-corruption adapter to the accounts API (ACCOUNTS_SERVICE_BASE_URL),
 * which owns account status. This service never reads account tables and
 * never stores balances or holder data.
 *
 * Expected provider contract: GET /api/v1/accounts/{accountId} returning
 * accountId, status (ACTIVE when usable), currency and debitAllowed; 404 for an
 * unknown account. Unknown fields are ignored. Calls carry this service's own
 * client-credentials token. Fails closed: errors other than 404 propagate and
 * the request is refused (503).
 */
public class AccountsServiceHttpAdapter implements DebtorAccountPort {

    static final String INTERACTION_ID_HEADER = "x-fapi-interaction-id";

    private final RestClient restClient;
    private final Supplier<String> serviceToken;

    public AccountsServiceHttpAdapter(RestClient restClient, Supplier<String> serviceToken) {
        this.restClient = restClient;
        this.serviceToken = serviceToken;
    }

    @Override
    public Optional<DebtorAccount> findDebtorAccount(String accountId) {
        try {
            AccountView view = restClient.get()
                    .uri("/api/v1/accounts/{accountId}", accountId)
                    .headers(this::addCallerHeaders)
                    .retrieve()
                    .body(AccountView.class);
            return Optional.ofNullable(view).map(v -> v.toDomain(accountId));
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        }
    }

    private void addCallerHeaders(HttpHeaders headers) {
        headers.setBearerAuth(serviceToken.get());
        String interactionId = MDC.get(CorrelationIdFilter.MDC_KEY);
        headers.set(INTERACTION_ID_HEADER, interactionId != null ? interactionId : UUID.randomUUID().toString());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountView(String accountId, String status, String currency, Boolean debitAllowed) {
        DebtorAccount toDomain(String requestedId) {
            return new DebtorAccount(accountId != null ? accountId : requestedId,
                    "ACTIVE".equalsIgnoreCase(status), Boolean.TRUE.equals(debitAllowed), currency);
        }
    }
}
