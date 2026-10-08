package com.enterprise.openfinance.recurringpayments.domain.model;

import com.enterprise.openfinance.recurringpayments.domain.command.CreateVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A mandate is only authorised under a consent the PSU authorised in the
 * consent service: the consent names the TPP, the PSU and the accounts; the
 * TPP's request may restate them but never widen them.
 */
@DisplayName("PSU consent binds a mandate")
class PsuConsentTest {

    private static final Instant NOW = Instant.parse("2026-02-09T10:00:00Z");
    private static final Instant CONSENT_EXPIRY = Instant.parse("2026-12-31T23:59:59Z");

    @Test
    @DisplayName("PSU, debtor account and expiry come from the consent when the request omits them")
    void termsComeFromTheConsent() {
        MandateTerms terms = consent(true, "TPP-001", Set.of("ACC-1")).termsFor(request("TPP-001", null, null, null), NOW);

        assertThat(terms.mandateId()).isEqualTo("CONS-AUTH-1");
        assertThat(terms.psuId()).isEqualTo("PSU-001");
        assertThat(terms.debtorAccountId()).isEqualTo("ACC-1");
        assertThat(terms.expiresAt()).isEqualTo(CONSENT_EXPIRY);
    }

    @Test
    @DisplayName("matching request values and an earlier expiry are accepted")
    void matchingValuesAreAccepted() {
        Instant earlier = Instant.parse("2026-06-30T00:00:00Z");
        MandateTerms terms = consent(true, "TPP-001", Set.of("ACC-1", "ACC-2"))
                .termsFor(request("TPP-001", "PSU-001", "ACC-2", earlier), NOW);

        assertThat(terms.debtorAccountId()).isEqualTo("ACC-2");
        assertThat(terms.expiresAt()).isEqualTo(earlier);
    }

    @Test
    @DisplayName("an unusable or lapsed consent, another TPP's consent or one without INITIATEVRP is refused")
    void unusableForeignOrOutOfScopeConsentIsRefused() {
        assertForbidden(consent(false, "TPP-001", Set.of("ACC-1")), request("TPP-001", null, null, null),
                "Consent is not authorised by the PSU");
        PsuConsent lapsed = new PsuConsent("CONS-AUTH-1", "TPP-001", "PSU-001", Set.of("INITIATEVRP"), Set.of("ACC-1"),
                NOW, true);
        assertForbidden(lapsed, request("TPP-001", null, null, null), "Consent is not authorised by the PSU");
        assertForbidden(consent(true, "TPP-OTHER", Set.of("ACC-1")), request("TPP-001", null, null, null),
                "Consent belongs to another TPP");
        PsuConsent aisOnly = new PsuConsent("CONS-AUTH-1", "TPP-001", "PSU-001", Set.of("READACCOUNTS"),
                Set.of("ACC-1"), CONSENT_EXPIRY, true);
        assertForbidden(aisOnly, request("TPP-001", null, null, null), "Consent does not grant INITIATEVRP");
    }

    @Test
    @DisplayName("a request cannot name another PSU, an account outside the consent or a later expiry")
    void requestCannotWidenTheConsent() {
        PsuConsent consent = consent(true, "TPP-001", Set.of("ACC-1"));
        assertForbidden(consent, request("TPP-001", "PSU-999", null, null), "PsuId does not match the consent");
        assertForbidden(consent, request("TPP-001", null, "ACC-999", null), "DebtorAccount is not covered by the consent");
        assertForbidden(consent, request("TPP-001", null, null, CONSENT_EXPIRY.plusSeconds(1)),
                "ExpiryDateTime is after the consent expiry");
        assertForbidden(consent(true, "TPP-001", Set.of()), request("TPP-001", null, null, null),
                "Consent covers no debtor account");
    }

    @Test
    @DisplayName("a migrated customer keeps the monolith id as text, e.g. \"1\"")
    void migratedCustomerIdIsKeptAsText() {
        PsuConsent consent = new PsuConsent("CONS-AUTH-1", "TPP-001", "1", Set.of("INITIATEVRP"), Set.of("ACC-1"),
                CONSENT_EXPIRY, true);

        assertThat(consent.termsFor(request("TPP-001", "1", null, null), NOW).psuId()).isEqualTo("1");
        assertForbidden(consent, request("TPP-001", "01", null, null), "PsuId does not match the consent");
    }

    @Test
    @DisplayName("the debtor account must be named when the consent covers several")
    void ambiguousDebtorAccountIsABadRequest() {
        assertThatThrownBy(() -> consent(true, "TPP-001", Set.of("ACC-1", "ACC-2"))
                .termsFor(request("TPP-001", null, null, null), NOW))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("DebtorAccount.Identification is required: the consent covers more than one account");
    }

    @Test
    @DisplayName("each collection re-checks that the consent still authorises the mandate")
    void collectionsReCheckTheConsent() {
        VrpConsent mandate = VrpConsent.authorise(consent(true, "TPP-001", Set.of("ACC-1")),
                request("TPP-001", null, null, null), NOW).mandate();

        consent(true, "TPP-001", Set.of("ACC-1")).ensureAuthorises(mandate, NOW);
        assertThatThrownBy(() -> consent(false, "TPP-001", Set.of("ACC-1")).ensureAuthorises(mandate, NOW))
                .isInstanceOf(ForbiddenException.class).hasMessage("Consent is not authorised by the PSU");
        assertThatThrownBy(() -> consent(true, "TPP-001", Set.of("ACC-2")).ensureAuthorises(mandate, NOW))
                .isInstanceOf(ForbiddenException.class).hasMessage("DebtorAccount is not covered by the consent");
        assertThatThrownBy(() -> consent(true, "TPP-OTHER", Set.of("ACC-1")).ensureAuthorises(mandate, NOW))
                .isInstanceOf(ForbiddenException.class).hasMessage("Consent belongs to another TPP");
    }

    @Test
    @DisplayName("scopes compare case-insensitively and blank ids are refused")
    void normalisesAndValidates() {
        PsuConsent consent = new PsuConsent(" CONS-AUTH-1 ", "TPP-001", "PSU-001", Set.of("initiatevrp"),
                Set.of(" ACC-1 "), CONSENT_EXPIRY, true);
        assertThat(consent.consentId()).isEqualTo("CONS-AUTH-1");
        assertThat(consent.termsFor(request("TPP-001", null, "ACC-1", null), NOW).debtorAccountId()).isEqualTo("ACC-1");
        assertThatThrownBy(() -> new PsuConsent(" ", "TPP-001", "PSU-001", Set.of(), Set.of(), CONSENT_EXPIRY, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("consentId");
        assertThatThrownBy(() -> new PsuConsent("C", " ", "PSU-001", Set.of(), Set.of(), CONSENT_EXPIRY, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("participantId");
        assertThatThrownBy(() -> new PsuConsent("C", "T", null, Set.of(), Set.of(), CONSENT_EXPIRY, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("customerId");
        assertThatThrownBy(() -> new PsuConsent("C", "T", "P", Set.of(), Set.of(), null, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expiresAt");
    }

    private static void assertForbidden(PsuConsent consent, CreateVrpConsentCommand command, String message) {
        assertThatThrownBy(() -> consent.termsFor(command, NOW))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(message);
    }

    static PsuConsent consent(boolean usable, String participant, Set<String> accounts) {
        return new PsuConsent("CONS-AUTH-1", participant, "PSU-001", Set.of("INITIATEVRP", "READACCOUNTS"),
                accounts, CONSENT_EXPIRY, usable);
    }

    static CreateVrpConsentCommand request(String tpp, String psu, String account, Instant expiry) {
        return new CreateVrpConsentCommand(tpp, "CONS-AUTH-1", psu, new BigDecimal("5000.00"), "AED", expiry, "ix-1",
                account);
    }
}
