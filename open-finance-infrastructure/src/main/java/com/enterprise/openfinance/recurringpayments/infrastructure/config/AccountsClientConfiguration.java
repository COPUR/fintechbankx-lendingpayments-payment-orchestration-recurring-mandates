package com.enterprise.openfinance.recurringpayments.infrastructure.config;

import com.enterprise.openfinance.recurringpayments.domain.port.out.DebtorAccountPort;
import com.enterprise.openfinance.recurringpayments.infrastructure.external.AccountsServiceHttpAdapter;
import com.enterprise.openfinance.recurringpayments.infrastructure.external.InMemoryDebtorAccountAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Wires the debtor-account check. Default: the accounts API over HTTP with
 * this service's own client-credentials token (Keycloak client
 * svc-pay-recurring-mandates, realm role service), never the TPP's token.
 * mandates.accounts.adapter=in-memory selects demo accounts for local runs.
 */
@Configuration
public class AccountsClientConfiguration {

    static final String SERVICE_PRINCIPAL = "svc-pay-recurring-mandates";

    /** Client-credentials manager shared by the accounts and consent clients. */
    @Bean
    OAuth2AuthorizedClientManager serviceAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientService authorizedClients) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build());
        return manager;
    }

    @Bean
    @ConditionalOnProperty(name = "mandates.accounts.adapter", havingValue = "in-memory")
    DebtorAccountPort inMemoryDebtorAccountPort() {
        return new InMemoryDebtorAccountAdapter();
    }

    @Configuration
    @ConditionalOnProperty(name = "mandates.accounts.adapter", havingValue = "http", matchIfMissing = true)
    static class Http {

        @Bean
        DebtorAccountPort accountsServiceDebtorAccountPort(
                RestClient.Builder builder,
                OAuth2AuthorizedClientManager serviceAuthorizedClientManager,
                @Value("${mandates.accounts.base-url}") String baseUrl,
                @Value("${mandates.accounts.path:" + AccountsServiceHttpAdapter.DEFAULT_PATH + "}") String path,
                @Value("${mandates.accounts.client-registration-id:accounts-service}") String registrationId,
                @Value("${mandates.accounts.connect-timeout:PT1S}") Duration connectTimeout,
                @Value("${mandates.accounts.read-timeout:PT2S}") Duration readTimeout) {
            ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(connectTimeout)
                    .withReadTimeout(readTimeout);
            RestClient client = builder
                    .baseUrl(baseUrl)
                    .requestFactory(ClientHttpRequestFactories.get(settings))
                    .build();
            return new AccountsServiceHttpAdapter(client, path, serviceToken(serviceAuthorizedClientManager, registrationId));
        }
    }

    /**
     * Client-credentials token for this service. The manager caches the token
     * and fetches a new one only when it is about to expire.
     */
    static Supplier<String> serviceToken(OAuth2AuthorizedClientManager manager, String registrationId) {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                .principal(SERVICE_PRINCIPAL)
                .build();
        return () -> {
            OAuth2AuthorizedClient client = manager.authorize(request);
            if (client == null) {
                throw new IllegalStateException("No service token for client registration " + registrationId);
            }
            return client.getAccessToken().getTokenValue();
        };
    }
}
