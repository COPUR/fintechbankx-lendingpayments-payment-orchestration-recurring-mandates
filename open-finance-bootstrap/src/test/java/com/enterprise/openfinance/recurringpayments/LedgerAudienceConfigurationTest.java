package com.enterprise.openfinance.recurringpayments;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accounts ledger accepts a token only when it carries aud svc-acc-accounts-ledger, which Keycloak
 * adds through the optional client scope aud-svc-acc-accounts-ledger. Only the ledger registration may ask for that
 * scope, and only the accounts adapter may use the ledger registration.
 */
class LedgerAudienceConfigurationTest {

    private static final String REGISTRATIONS = "spring.security.oauth2.client.registration";
    private static final String LEDGER_REGISTRATION = "accounts-service";
    private static final String LEDGER_SCOPE = "aud-svc-acc-accounts-ledger";

    @Test
    void onlyTheLedgerRegistrationRequestsTheLedgerAudienceScope() throws Exception {
        Map<String, ClientRegistration> registrations = binder(applicationDocuments())
            .bind(REGISTRATIONS, Bindable.mapOf(String.class, ClientRegistration.class))
            .orElse(Map.of());
        Map<String, List<String>> scoped = new TreeMap<>();
        registrations.forEach((id, registration) -> {
            if (registration.scope() != null && !registration.scope().isEmpty()) {
                scoped.put(id, registration.scope());
            }
        });

        assertThat(scoped).containsOnlyKeys(LEDGER_REGISTRATION);
        assertThat(scoped.get(LEDGER_REGISTRATION)).containsExactly(LEDGER_SCOPE);
    }

    @Test
    void theLedgerRegistrationIsUsedByTheAccountsAdapterOnly() throws Exception {
        Map<String, String> references = registrationReferences(applicationDocuments());

        assertThat(references.entrySet().stream()
            .filter(reference -> LEDGER_REGISTRATION.equals(reference.getValue()))
            .map(Map.Entry::getKey)
            .toList())
            .containsExactly("mandates.accounts.client-registration-id");
    }

    /** The scope as YAML binds it: one string or a list. Any registration may carry it, so each is checked. */
    record ClientRegistration(List<String> scope) {
    }

    private static List<PropertySource<?>> applicationDocuments() throws Exception {
        return new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml")).stream()
            .filter(document -> document.getProperty("spring.config.activate.on-profile") == null)
            .toList();
    }

    private static Binder binder(List<PropertySource<?>> documents) {
        StandardEnvironment environment = new StandardEnvironment();
        documents.forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment);
    }

    /** Every property whose name ends in registration-id, with its value, from application.yml only. */
    private static Map<String, String> registrationReferences(List<PropertySource<?>> documents) {
        Map<String, String> references = new TreeMap<>();
        for (PropertySource<?> document : documents) {
            for (String name : ((EnumerablePropertySource<?>) document).getPropertyNames()) {
                if (name.endsWith("registration-id")) {
                    references.put(name, String.valueOf(document.getProperty(name)));
                }
            }
        }
        return references;
    }
}
