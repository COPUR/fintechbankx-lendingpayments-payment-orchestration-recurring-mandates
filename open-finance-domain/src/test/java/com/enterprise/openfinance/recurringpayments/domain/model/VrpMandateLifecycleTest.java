package com.enterprise.openfinance.recurringpayments.domain.model;

import com.enterprise.openfinance.recurringpayments.domain.command.CreateVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.SubmitVrpPaymentCommand;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateCreated;
import com.enterprise.openfinance.recurringpayments.domain.event.MandatePaymentAccepted;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateRevoked;
import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mandate (VrpConsent) guards its own lifecycle and its monthly limit and
 * raises the events the service publishes under evt.pay.mandate.
 */
class VrpMandateLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-02-09T10:00:00Z");
    private static final Instant EXPIRY = Instant.parse("2026-12-31T23:59:59Z");
    private static final PsuConsent CONSENT = PsuConsentTest.consent(true, "TPP-001", java.util.Set.of("ACC-001"));

    @Test
    void authoriseCreatesAnAuthorisedMandateAtVersionZeroAndRaisesCreated() {
        MandateChange change = VrpConsent.authorise(CONSENT, createCommand("ACC-001"), NOW);

        VrpConsent mandate = change.mandate();
        assertThat(mandate.status()).isEqualTo(VrpConsentStatus.AUTHORISED);
        assertThat(mandate.version()).isZero();
        assertThat(mandate.debtorAccountId()).isEqualTo("ACC-001");
        assertThat(change.events()).singleElement().isInstanceOfSatisfying(MandateCreated.class, created -> {
            assertThat(created.mandateId()).isEqualTo("CONS-AUTH-1");
            assertThat(created.aggregateVersion()).isZero();
            assertThat(created.maxAmount()).isEqualByComparingTo("5000.00");
            assertThat(created.currency()).isEqualTo("AED");
            assertThat(created.expiresAt()).isEqualTo(EXPIRY);
            assertThat(created.occurredAt()).isEqualTo(NOW);
            assertThat(created.eventId()).isNotNull();
        });
    }

    @Test
    void authoriseRejectsAnExpiryThatIsNotInTheFuture() {
        CreateVrpConsentCommand alreadyExpired = new CreateVrpConsentCommand("TPP-001", "CONS-AUTH-1", "PSU-001", new BigDecimal("5000.00"), "AED", NOW, "ix-1", null);

        assertThatThrownBy(() -> VrpConsent.authorise(CONSENT, alreadyExpired, NOW))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("ExpiryDateTime must be in the future");
    }

    @Test
    void revokeMovesToRevokedBumpsTheVersionAndIsIdempotent() {
        VrpConsent mandate = VrpConsent.authorise(CONSENT, createCommand(null), NOW).mandate();
        Instant at = NOW.plusSeconds(60);

        MandateChange revoked = mandate.revoke(at, "Customer request");

        assertThat(revoked.mandate().status()).isEqualTo(VrpConsentStatus.REVOKED);
        assertThat(revoked.mandate().revokedAt()).isEqualTo(at);
        assertThat(revoked.mandate().version()).isEqualTo(1L);
        assertThat(revoked.events()).singleElement().isInstanceOfSatisfying(MandateRevoked.class, event -> {
            assertThat(event.aggregateVersion()).isEqualTo(1L);
            assertThat(event.reason()).isEqualTo("Customer request");
            assertThat(event.occurredAt()).isEqualTo(at);
        });

        MandateChange again = revoked.mandate().revoke(at.plusSeconds(60), "duplicate");
        assertThat(again.mandate()).isSameAs(revoked.mandate());
        assertThat(again.events()).isEmpty();
    }

    @Test
    void paymentThatExactlyReachesTheMonthlyLimitIsAccepted() {
        VrpConsent mandate = VrpConsent.authorise(CONSENT, createCommand(null), NOW).mandate();

        PaymentAuthorisation authorisation = mandate.authorisePayment(
                "PAY-VRP-1", submit("1000.00", "AED"), new BigDecimal("4000.00"), NOW);

        VrpPayment payment = authorisation.payment();
        assertThat(payment.status()).isEqualTo(VrpPaymentStatus.ACCEPTED);
        assertThat(payment.amount()).isEqualByComparingTo("1000.00");
        assertThat(payment.periodKey()).isEqualTo("2026-02");
        assertThat(payment.idempotencyKey()).isEqualTo("IDEMP-1");
        assertThat(authorisation.mandate().version()).isEqualTo(1L);
        assertThat(authorisation.event()).isInstanceOfSatisfying(MandatePaymentAccepted.class, event -> {
            assertThat(event.paymentId()).isEqualTo("PAY-VRP-1");
            assertThat(event.amount()).isEqualByComparingTo("1000.00");
            assertThat(event.periodKey()).isEqualTo("2026-02");
            assertThat(event.periodTotal()).isEqualByComparingTo("5000.00");
            assertThat(event.aggregateVersion()).isEqualTo(1L);
        });
    }

    @Test
    void paymentOneFilsOverTheMonthlyLimitIsRejected() {
        VrpConsent mandate = VrpConsent.authorise(CONSENT, createCommand(null), NOW).mandate();

        assertThatThrownBy(() -> mandate.authorisePayment(
                "PAY-VRP-1", submit("1000.01", "AED"), new BigDecimal("4000.00"), NOW))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Limit Exceeded");
    }

    @Test
    void amountsMayNotCarryMoreDecimalsThanTheCurrencyAllows() {
        CreateVrpConsentCommand subFils = new CreateVrpConsentCommand("TPP-001", "CONS-AUTH-1", "PSU-001", new BigDecimal("5000.005"), "AED", EXPIRY, "ix-1", null);
        assertThatThrownBy(() -> VrpConsent.authorise(CONSENT, subFils, NOW))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Amount has more than 2 decimal places for AED");

        VrpConsent mandate = VrpConsent.authorise(CONSENT, createCommand(null), NOW).mandate();
        assertThatThrownBy(() -> mandate.authorisePayment("PAY-1", submit("10.001", "AED"), BigDecimal.ZERO, NOW))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Amount has more than 2 decimal places for AED");
        // Trailing zeros are not extra precision.
        assertThat(mandate.authorisePayment("PAY-1", submit("10.0000", "AED"), BigDecimal.ZERO, NOW).payment().amount())
                .isEqualByComparingTo("10.00");

        CreateVrpConsentCommand unknownCurrency = new CreateVrpConsentCommand("TPP-001", "CONS-AUTH-1", "PSU-001", new BigDecimal("5000.00"), "XYZ1", EXPIRY, "ix-1", null);
        assertThatThrownBy(() -> VrpConsent.authorise(CONSENT, unknownCurrency, NOW))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Unknown currency XYZ1");
    }

    @Test
    void periodIsTheUtcCalendarMonth() {
        assertThat(VrpConsent.periodKeyOf(Instant.parse("2026-02-28T23:59:59Z"))).isEqualTo("2026-02");
        assertThat(VrpConsent.periodKeyOf(Instant.parse("2026-03-01T00:00:00Z"))).isEqualTo("2026-03");
    }

    @Test
    void paymentIsRejectedForAnotherTppARevokedOrExpiredMandateOrAnotherCurrency() {
        VrpConsent mandate = VrpConsent.authorise(CONSENT, createCommand(null), NOW).mandate();

        SubmitVrpPaymentCommand otherTpp = new SubmitVrpPaymentCommand(
                "TPP-OTHER", "CONS-AUTH-1", "IDEMP-1", new BigDecimal("10.00"), "AED", "ix-2");
        assertThatThrownBy(() -> mandate.authorisePayment("PAY-1", otherTpp, BigDecimal.ZERO, NOW))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("participant mismatch");

        VrpConsent revoked = mandate.revoke(NOW, "stop").mandate();
        assertThatThrownBy(() -> revoked.authorisePayment("PAY-1", submit("10.00", "AED"), BigDecimal.ZERO, NOW))
                .isInstanceOf(ForbiddenException.class).hasMessage("Consent Revoked");

        assertThatThrownBy(() -> mandate.authorisePayment("PAY-1", submit("10.00", "AED"), BigDecimal.ZERO, EXPIRY))
                .isInstanceOf(ForbiddenException.class).hasMessage("Consent expired");

        assertThatThrownBy(() -> mandate.authorisePayment("PAY-1", submit("10.00", "USD"), BigDecimal.ZERO, NOW))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Currency mismatch");
    }

    @Test
    void debtorAccountMustBeActiveDebitableAndInTheMandateCurrency() {
        new DebtorAccount("ACC-001", true, true, "AED").ensureDebitableIn("AED");

        assertThatThrownBy(() -> new DebtorAccount("ACC-001", false, true, "AED").ensureDebitableIn("AED"))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Debtor account is not active");
        assertThatThrownBy(() -> new DebtorAccount("ACC-001", true, false, "AED").ensureDebitableIn("AED"))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Debtor account does not allow debits");
        assertThatThrownBy(() -> new DebtorAccount("ACC-001", true, true, "USD").ensureDebitableIn("AED"))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Debtor account currency mismatch");
    }

    @Test
    void rehydratedMandateKeepsItsVersion() {
        VrpConsent stored = new VrpConsent("CONS-AUTH-1", "TPP-001", "PSU-001", new BigDecimal("5000.00"), "AED",
                VrpConsentStatus.AUTHORISED, EXPIRY, null, "ACC-001", 7L);

        assertThat(stored.version()).isEqualTo(7L);
        assertThat(stored.revoke(NOW, "stop").mandate().version()).isEqualTo(8L);
        assertThatThrownBy(() -> new VrpConsent("CONS-AUTH-1", "TPP-001", "PSU-001", new BigDecimal("5000.00"), "AED",
                VrpConsentStatus.AUTHORISED, EXPIRY, null, null, -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
    }

    private static CreateVrpConsentCommand createCommand(String debtorAccountId) {
        return new CreateVrpConsentCommand("TPP-001", "CONS-AUTH-1", "PSU-001", new BigDecimal("5000.00"), "AED", EXPIRY, "ix-1",
                debtorAccountId);
    }

    private static SubmitVrpPaymentCommand submit(String amount, String currency) {
        return new SubmitVrpPaymentCommand("TPP-001", "CONS-AUTH-1", "IDEMP-1", new BigDecimal(amount), currency, "ix-2");
    }
}
