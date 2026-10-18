package com.enterprise.openfinance.recurringpayments.infrastructure.config;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({RecurringPaymentsCacheProperties.class, RecurringPaymentsPolicyProperties.class})
public class RecurringPaymentsConfiguration {

    @Bean
    public Clock vrpClock() {
        return Clock.systemUTC();
    }

    @Bean
    public VrpSettings vrpSettings(RecurringPaymentsPolicyProperties policyProperties,
                                   RecurringPaymentsCacheProperties cacheProperties,
                                   @Value("${mandates.limit-period-zone:Asia/Dubai}") ZoneId limitPeriodZone) {
        return new VrpSettings(policyProperties.getIdempotencyTtl(), cacheProperties.getTtl(), limitPeriodZone);
    }
}
