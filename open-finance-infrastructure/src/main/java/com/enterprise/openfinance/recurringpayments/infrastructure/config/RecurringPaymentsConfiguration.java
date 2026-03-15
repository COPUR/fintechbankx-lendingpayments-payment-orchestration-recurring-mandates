package com.enterprise.openfinance.recurringpayments.infrastructure.config;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({RecurringPaymentsCacheProperties.class, RecurringPaymentsPolicyProperties.class})
public class RecurringPaymentsConfiguration {

    @Bean
    public Clock vrpClock() {
        return Clock.systemUTC();
    }

    @Bean
    public VrpSettings vrpSettings(RecurringPaymentsPolicyProperties policyProperties, RecurringPaymentsCacheProperties cacheProperties) {
        return new VrpSettings(policyProperties.getIdempotencyTtl(), cacheProperties.getTtl());
    }
}
