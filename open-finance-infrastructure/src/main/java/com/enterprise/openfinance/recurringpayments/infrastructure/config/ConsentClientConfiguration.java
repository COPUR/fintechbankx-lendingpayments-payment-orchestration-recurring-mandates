package com.enterprise.openfinance.recurringpayments.infrastructure.config;

import com.enterprise.openfinance.recurringpayments.domain.port.out.PsuConsentPort;
import com.enterprise.openfinance.recurringpayments.infrastructure.external.HttpPsuConsentAdapter;
import com.enterprise.openfinance.recurringpayments.infrastructure.external.InMemoryPsuConsentAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Consent reads. Default: consent-authorization-service over HTTP
 * (CONSENT_SERVICE_BASE_URL) with this service's client-credentials token
 * (client registration consent-service). mandates.consent.adapter=in-memory
 * seeds demo consents for local runs only.
 */
@Configuration
public class ConsentClientConfiguration {

    @Bean
    @ConditionalOnProperty(name = "mandates.consent.adapter", havingValue = "in-memory")
    PsuConsentPort inMemoryPsuConsentPort() {
        return new InMemoryPsuConsentAdapter();
    }

    @Bean
    @ConditionalOnProperty(name = "mandates.consent.adapter", havingValue = "http", matchIfMissing = true)
    PsuConsentPort httpPsuConsentPort(
            RestClient.Builder builder,
            OAuth2AuthorizedClientManager serviceAuthorizedClientManager,
            @Value("${mandates.consent.base-url}") String baseUrl,
            @Value("${mandates.consent.client-registration-id:consent-service}") String registrationId,
            @Value("${mandates.consent.connect-timeout:PT1S}") Duration connectTimeout,
            @Value("${mandates.consent.read-timeout:PT2S}") Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        RestClient client = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
        return new HttpPsuConsentAdapter(client,
                AccountsClientConfiguration.serviceToken(serviceAuthorizedClientManager, registrationId));
    }
}
