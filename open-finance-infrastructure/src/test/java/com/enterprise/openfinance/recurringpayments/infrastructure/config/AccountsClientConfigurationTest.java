package com.enterprise.openfinance.recurringpayments.infrastructure.config;

import com.enterprise.openfinance.recurringpayments.domain.port.out.DebtorAccountPort;
import com.enterprise.openfinance.recurringpayments.infrastructure.external.AccountsServiceHttpAdapter;
import com.enterprise.openfinance.recurringpayments.infrastructure.external.InMemoryDebtorAccountAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountsClientConfigurationTest {

    private static final ClientRegistration REGISTRATION = ClientRegistration.withRegistrationId("accounts-service")
            .clientId("svc-pay-recurring-mandates")
            .clientSecret("test-only")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri("http://idp.test/token")
            .build();

    @Test
    void serviceTokenIsThisServicesClientCredentialsTokenNeverTheCallers() {
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "svc-token",
                Instant.now(), Instant.now().plusSeconds(300));
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenAnswer(invocation -> {
            OAuth2AuthorizeRequest request = invocation.getArgument(0);
            assertThat(request.getClientRegistrationId()).isEqualTo("accounts-service");
            assertThat(request.getPrincipal().getName()).isEqualTo("svc-pay-recurring-mandates");
            return new OAuth2AuthorizedClient(REGISTRATION, "svc-pay-recurring-mandates", token);
        });

        Supplier<String> supplier = AccountsClientConfiguration.serviceToken(manager, "accounts-service");

        assertThat(supplier.get()).isEqualTo("svc-token");
    }

    @Test
    void missingServiceTokenFailsTheCall() {
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);

        assertThatThrownBy(() -> AccountsClientConfiguration.serviceToken(manager, "accounts-service").get())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wiresTheHttpAdapterWithAClientCredentialsManagerOrTheInMemoryDemo() {
        AccountsClientConfiguration.Http http = new AccountsClientConfiguration.Http();
        InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(REGISTRATION);
        OAuth2AuthorizedClientManager manager = http.serviceAuthorizedClientManager(registrations,
                new InMemoryOAuth2AuthorizedClientService(registrations));

        DebtorAccountPort port = http.accountsServiceDebtorAccountPort(RestClient.builder(), manager,
                "http://accounts.test", "accounts-service", Duration.ofSeconds(1), Duration.ofSeconds(2));

        assertThat(port).isInstanceOf(AccountsServiceHttpAdapter.class);
        assertThat(new AccountsClientConfiguration().inMemoryDebtorAccountPort()).isInstanceOf(InMemoryDebtorAccountAdapter.class);
    }
}
