package com.enterprise.openfinance.recurringpayments.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each outbound adapter asks for the token of its own registration. The consent adapter uses
 * consent-service, which carries no ledger scope; the accounts adapter uses accounts-service, the only
 * registration that asks for aud-svc-acc-accounts-ledger. A bean that reads the other key, or
 * another default, fails here.
 */
class OutboundRegistrationIdTest {

    private static final String CONSENT_REGISTRATION = "mandates.consent.client-registration-id=consent-service";
    private static final String ACCOUNTS_REGISTRATION = "mandates.accounts.client-registration-id=accounts-service";

    @Test
    void theConsentAdapterReadsTheConsentRegistration() throws NoSuchMethodException {
        assertThat(registrationOf(ConsentClientConfiguration.class, "httpPsuConsentPort"))
            .isEqualTo(CONSENT_REGISTRATION);
    }

    @Test
    void theAccountsAdapterReadsTheAccountsRegistration() throws NoSuchMethodException {
        assertThat(registrationOf(AccountsClientConfiguration.Http.class, "accountsServiceDebtorAccountPort"))
            .isEqualTo(ACCOUNTS_REGISTRATION);
    }

    /** The key and default of the registration id that a bean method reads, as key=default. */
    private static String registrationOf(Class<?> configuration, String beanMethod) throws NoSuchMethodException {
        Method method = Arrays.stream(configuration.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(beanMethod))
            .findFirst()
            .orElseThrow(() -> new NoSuchMethodException(configuration.getName() + "#" + beanMethod));
        for (Parameter parameter : method.getParameters()) {
            Value value = parameter.getAnnotation(Value.class);
            if (value != null && value.value().contains("registration-id")) {
                return value.value().replaceAll("^\\$\\{|}$", "").replaceFirst(":", "=");
            }
        }
        throw new AssertionError(beanMethod + " reads no registration id");
    }
}
