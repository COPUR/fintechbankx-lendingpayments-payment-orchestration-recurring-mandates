package com.enterprise.openfinance.recurringpayments.infrastructure.config;

import com.enterprise.openfinance.recurringpayments.infrastructure.external.HttpPsuConsentAdapter;
import com.enterprise.openfinance.recurringpayments.infrastructure.external.InMemoryPsuConsentAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConsentClientConfigurationTest {

    @Test
    void wiresTheHttpConsentAdapterOrTheInMemoryDemo() {
        ConsentClientConfiguration configuration = new ConsentClientConfiguration();

        assertThat(configuration.httpPsuConsentPort(RestClient.builder(), mock(OAuth2AuthorizedClientManager.class),
                "http://consent.test", "consent-service", Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .isInstanceOf(HttpPsuConsentAdapter.class);
        assertThat(configuration.inMemoryPsuConsentPort()).isInstanceOf(InMemoryPsuConsentAdapter.class);
    }
}
