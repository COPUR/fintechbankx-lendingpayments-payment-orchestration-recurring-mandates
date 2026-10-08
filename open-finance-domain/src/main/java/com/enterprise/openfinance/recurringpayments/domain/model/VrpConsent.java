package com.enterprise.openfinance.recurringpayments.domain.model;

import com.enterprise.openfinance.recurringpayments.domain.command.CreateVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.SubmitVrpPaymentCommand;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateCreated;
import com.enterprise.openfinance.recurringpayments.domain.event.MandatePaymentAccepted;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateRevoked;
import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Locale;
import java.util.List;
import java.util.UUID;

/**
 * Mandate aggregate (variable recurring payment consent). Immutable: every
 * change returns the next state at version + 1 together with the event it
 * raised. version is the optimistic-concurrency token the persistence adapter
 * compares on save.
 *
 * Limit rule: the accepted total per UTC calendar month may not exceed maxAmount.
 */
public record VrpConsent(
        String consentId,
        String tppId,
        String psuId,
        BigDecimal maxAmount,
        String currency,
        VrpConsentStatus status,
        Instant expiresAt,
        Instant revokedAt,
        String debtorAccountId,
        long version
) {

    public VrpConsent {
        if (isBlank(consentId)) {
            throw new IllegalArgumentException("consentId is required");
        }
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (isBlank(psuId)) {
            throw new IllegalArgumentException("psuId is required");
        }
        if (maxAmount == null || maxAmount.signum() <= 0) {
            throw new IllegalArgumentException("maxAmount must be positive");
        }
        if (isBlank(currency)) {
            throw new IllegalArgumentException("currency is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }

        consentId = consentId.trim();
        tppId = tppId.trim();
        psuId = psuId.trim();
        currency = currency.trim().toUpperCase(Locale.ROOT);
        debtorAccountId = isBlank(debtorAccountId) ? null : debtorAccountId.trim();
    }

    /** A mandate without a linked debtor account, at version 0. */
    public VrpConsent(String consentId,
                      String tppId,
                      String psuId,
                      BigDecimal maxAmount,
                      String currency,
                      VrpConsentStatus status,
                      Instant expiresAt,
                      Instant revokedAt) {
        this(consentId, tppId, psuId, maxAmount, currency, status, expiresAt, revokedAt, null, 0L);
    }

    public static MandateChange authorise(String consentId, CreateVrpConsentCommand command, Instant now) {
        if (!command.expiresAt().isAfter(now)) {
            throw new BusinessRuleViolationException("ExpiryDateTime must be in the future");
        }
        ensureMinorUnits(command.maxAmount(), command.currency());
        VrpConsent mandate = new VrpConsent(
                consentId,
                command.tppId(),
                command.psuId(),
                command.maxAmount(),
                command.currency(),
                VrpConsentStatus.AUTHORISED,
                command.expiresAt(),
                null,
                command.debtorAccountId(),
                0L
        );
        MandateCreated created = new MandateCreated(
                UUID.randomUUID(),
                mandate.consentId(),
                mandate.version(),
                now,
                mandate.tppId(),
                mandate.psuId(),
                mandate.maxAmount(),
                mandate.currency(),
                mandate.expiresAt(),
                mandate.debtorAccountId() != null
        );
        return new MandateChange(mandate, List.of(created));
    }

    public static String periodKeyOf(Instant at) {
        return YearMonth.from(at.atZone(ZoneOffset.UTC)).toString();
    }

    public boolean belongsToTpp(String candidateTppId) {
        return tppId.equals(candidateTppId);
    }

    public boolean isActive(Instant now) {
        return status == VrpConsentStatus.AUTHORISED && expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return status == VrpConsentStatus.REVOKED;
    }

    public void ensureOwnedBy(String candidateTppId) {
        if (!belongsToTpp(candidateTppId)) {
            throw new ForbiddenException("Consent participant mismatch");
        }
    }

    /** Checks that do not depend on the period total; safe to run before taking the mandate lock. */
    public void ensureCanCollect(SubmitVrpPaymentCommand command, Instant now) {
        ensureOwnedBy(command.tppId());
        if (isRevoked()) {
            throw new ForbiddenException("Consent Revoked");
        }
        if (!isActive(now)) {
            throw new ForbiddenException("Consent expired");
        }
        if (!currency.equalsIgnoreCase(command.currency())) {
            throw new BusinessRuleViolationException("Currency mismatch");
        }
    }

    /** Revokes the mandate. Revoking a revoked mandate changes nothing and raises nothing. */
    public MandateChange revoke(Instant at, String reason) {
        if (isRevoked()) {
            return new MandateChange(this, List.of());
        }
        VrpConsent revoked = new VrpConsent(consentId, tppId, psuId, maxAmount, currency,
                VrpConsentStatus.REVOKED, expiresAt, at, debtorAccountId, version + 1);
        return new MandateChange(revoked, List.of(
                new MandateRevoked(UUID.randomUUID(), consentId, revoked.version(), at, tppId, reason)));
    }

    /**
     * Accepts a collection if the mandate is usable and the month's accepted
     * total including this amount stays within maxAmount.
     *
     * @param acceptedInPeriod total already accepted under this mandate in the period of {@code now}
     */
    public PaymentAuthorisation authorisePayment(String paymentId,
                                                 SubmitVrpPaymentCommand command,
                                                 BigDecimal acceptedInPeriod,
                                                 Instant now) {
        ensureCanCollect(command, now);
        ensureMinorUnits(command.amount(), currency);
        BigDecimal periodTotal = acceptedInPeriod.add(command.amount());
        if (periodTotal.compareTo(maxAmount) > 0) {
            throw new BusinessRuleViolationException("Limit Exceeded");
        }

        String periodKey = periodKeyOf(now);
        VrpPayment payment = new VrpPayment(paymentId, consentId, command.tppId(), command.idempotencyKey(),
                command.amount(), command.currency(), periodKey, VrpPaymentStatus.ACCEPTED, now);
        VrpConsent next = new VrpConsent(consentId, tppId, psuId, maxAmount, currency, status, expiresAt,
                revokedAt, debtorAccountId, version + 1);
        MandatePaymentAccepted event = new MandatePaymentAccepted(UUID.randomUUID(), consentId, next.version(), now,
                payment.paymentId(), payment.tppId(), payment.amount(), payment.currency(), periodKey, periodTotal);
        return new PaymentAuthorisation(next, payment, event);
    }

    /**
     * Amounts may not carry more decimals than the currency's minor unit
     * (2 for AED), so nothing is rounded silently when stored or shown.
     */
    static void ensureMinorUnits(BigDecimal amount, String currencyCode) {
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessRuleViolationException("Unknown currency " + currencyCode);
        }
        int minorUnits = Math.max(currency.getDefaultFractionDigits(), 0);
        if (amount.stripTrailingZeros().scale() > minorUnits) {
            throw new BusinessRuleViolationException(
                    "Amount has more than " + minorUnits + " decimal places for " + currency.getCurrencyCode());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
