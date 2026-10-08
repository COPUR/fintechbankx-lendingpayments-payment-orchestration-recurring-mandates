package com.enterprise.openfinance.recurringpayments.infrastructure.external;

import com.enterprise.openfinance.recurringpayments.domain.model.DebtorAccount;
import com.enterprise.openfinance.recurringpayments.infrastructure.observability.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AccountsServiceHttpAdapterTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://accounts.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AccountsServiceHttpAdapter adapter = new AccountsServiceHttpAdapter(builder.build(),
            AccountsServiceHttpAdapter.DEFAULT_PATH, () -> "svc-token");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void readsStatusCurrencyAndDebitFlagWithTheServiceTokenAndInteractionId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "ix-acc");
        server.expect(requestTo("http://accounts.test/api/v1/accounts/ACC-1"))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andExpect(header("x-fapi-interaction-id", "ix-acc"))
                .andRespond(withSuccess("""
                        {"accountId": "ACC-1", "status": "ACTIVE", "currency": "AED", "debitAllowed": true,
                         "holderName": "ignored"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.findDebtorAccount("ACC-1")).contains(new DebtorAccount("ACC-1", true, true, "AED"));
        server.verify();
    }

    @Test
    void frozenAccountIsReportedAsNotActiveAndUnknownAccountAsEmpty() {
        server.expect(requestTo("http://accounts.test/api/v1/accounts/ACC-2"))
                .andRespond(withSuccess("{\"status\": \"FROZEN\", \"currency\": \"AED\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://accounts.test/api/v1/accounts/ACC-404"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adapter.findDebtorAccount("ACC-2")).contains(new DebtorAccount("ACC-2", false, false, "AED"));
        assertThat(adapter.findDebtorAccount("ACC-404")).isEmpty();
    }

    @Test
    void serverErrorsPropagateSoTheRequestFailsClosed() {
        server.expect(requestTo("http://accounts.test/api/v1/accounts/ACC-3"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> adapter.findDebtorAccount("ACC-3")).isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void thePathIsConfigurableSoTheInterimSystemOfRecordCanServeIt() {
        RestClient.Builder interimBuilder = RestClient.builder().baseUrl("http://interim.test");
        MockRestServiceServer interim = MockRestServiceServer.bindTo(interimBuilder).build();
        AccountsServiceHttpAdapter interimAdapter = new AccountsServiceHttpAdapter(interimBuilder.build(),
                "/internal/accounts/{accountId}/status", () -> "svc-token");
        interim.expect(requestTo("http://interim.test/internal/accounts/ACC-9/status"))
                .andRespond(withSuccess("{\"status\": \"ACTIVE\", \"currency\": \"AED\", \"debitAllowed\": true}",
                        MediaType.APPLICATION_JSON));

        assertThat(interimAdapter.findDebtorAccount("ACC-9")).contains(new DebtorAccount("ACC-9", true, true, "AED"));
        assertThat(AccountsServiceHttpAdapter.DEFAULT_PATH).isEqualTo("/api/v1/accounts/{accountId}");
        interim.verify();
    }

    @Test
    void aPathWithoutTheAccountIdPlaceholderIsRejectedAtStartup() {
        assertThatThrownBy(() -> new AccountsServiceHttpAdapter(builder.build(), "/api/v1/accounts", () -> "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{accountId}");
    }

    @Test
    void inMemoryDemoAccounts() {
        InMemoryDebtorAccountAdapter demo = new InMemoryDebtorAccountAdapter();

        assertThat(demo.findDebtorAccount("ACC-AED-ACTIVE")).hasValueSatisfying(a -> assertThat(a.active()).isTrue());
        assertThat(demo.findDebtorAccount("ACC-AED-BLOCKED")).hasValueSatisfying(a -> assertThat(a.active()).isFalse());
        assertThat(demo.findDebtorAccount("ACC-NONE")).isEmpty();
    }
}
