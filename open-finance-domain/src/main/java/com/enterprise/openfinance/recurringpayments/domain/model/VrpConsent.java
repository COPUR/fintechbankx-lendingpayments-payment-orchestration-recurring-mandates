package com.enterprise.openfinance.recurringpayments.domain.model;

import com.enterprise.openfinance.recurringpayments.domain.command.CreateVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.SubmitVrpPaymentCommand;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateCreated;
import com.enterprise.openfinance.recurringpayments.domain.event.MandatePaymentAccepted;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateRevoked;
import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ConsentNotUsableException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Mandate aggregate (variable recurring payment consent). Immutable: every
 * change returns the next state at version + 1 together with the event it
 * raised. version is the optimistic-concurrency token the persistence adapter
 * compares on save.
 *
 * Limit rule: the accepted total per calendar month in the limit-period zone
 * (Asia/Dubai by default) may not exceed the limit.
 */
public record VrpConsent(
        String consentId,
        String tppId,
        String psuId,
        Money limit,
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
        if (limit == null || !limit.isPositive()) {
            throw new IllegalArgumentException("maxAmount (limit) must be positive");
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
        debtorAccountId = isBlank(debtorAccountId) ? null : debtorAccountId.trim();
    }

    /** Rehydration from stored primitives (persistence, tests). */
    public VrpConsent(String consentId,
                      String tppId,
                      String psuId,
                      BigDecimal maxAmount,
                      String currency,
                      VrpConsentStatus status,
                      Instant expiresAt,
                      Instant revokedAt,
                      String debtorAccountId,
                      long version) {
        this(consentId, tppId, psuId, maxAmount == null ? null : Money.of(maxAmount, currency), status, expiresAt,
                revokedAt, debtorAccountId, version);
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

    /**
     * Authorises a mandate under a consent the PSU authorised in the consent
     * service. The mandate takes the consent's id, PSU and debtor account
     * (see {@link PsuConsent#termsFor}); only the limit comes from the TPP.
     */
    public static MandateChange authorise(PsuConsent consent, CreateVrpConsentCommand command, Instant now) {
        MandateTerms terms = consent.termsFor(command, now);
        if (!terms.expiresAt().isAfter(now)) {
            throw new BusinessRuleViolationException("ExpiryDateTime must be in the future");
        }
        VrpConsent mandate = new VrpConsent(
                terms.mandateId(),
                terms.tppId(),
                terms.psuId(),
                Money.of(command.maxAmount(), command.currency()),
                VrpConsentStatus.AUTHORISED,
                terms.expiresAt(),
                null,
                terms.debtorAccountId(),
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

    public BigDecimal maxAmount() {
        return limit.amount();
    }

    public String currency() {
        return limit.currencyCode();
    }

    /** UAE mandates count their monthly limit in local (Gulf Standard) time. */
    public static final ZoneId DEFAULT_LIMIT_PERIOD_ZONE = ZoneId.of("Asia/Dubai");

    /** The limit period is the calendar month of {@code at} in {@code zone}, e.g. "2026-03". */
    public static String periodKeyOf(Instant at, ZoneId zone) {
        return YearMonth.from(at.atZone(zone)).toString();
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
            // Same answer as an unknown mandate: another TPP cannot probe mandate ids.
            throw new ConsentNotUsableException(ConsentNotUsableException.Reason.OTHER_TPP);
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
        if (!limit.hasCurrency(command.currency())) {
            throw new BusinessRuleViolationException("Currency mismatch");
        }
    }

    /** Revokes the mandate. Revoking a revoked mandate changes nothing and raises nothing. */
    public MandateChange revoke(Instant at, String reason) {
        if (isRevoked()) {
            return new MandateChange(this, List.of());
        }
        VrpConsent revoked = new VrpConsent(consentId, tppId, psuId, limit,
                VrpConsentStatus.REVOKED, expiresAt, at, debtorAccountId, version + 1);
        return new MandateChange(revoked, List.of(
                new MandateRevoked(UUID.randomUUID(), consentId, revoked.version(), at, tppId, reason)));
    }

    /**
     * Accepts a collection if the mandate is usable and the month's accepted
     * total including this amount stays within the limit.
     *
     * @param acceptedInPeriod total already accepted under this mandate in the period of {@code now}
     * @param periodZone       zone whose calendar month is the limit period
     */
    public PaymentAuthorisation authorisePayment(String paymentId,
                                                 SubmitVrpPaymentCommand command,
                                                 BigDecimal acceptedInPeriod,
                                                 Instant now,
                                                 ZoneId periodZone) {
        ensureCanCollect(command, now);
        Money instructed = Money.of(command.amount(), limit.currencyCode());
        Money periodTotal = Money.of(acceptedInPeriod, limit.currencyCode()).plus(instructed);
        if (periodTotal.exceeds(limit)) {
            throw new BusinessRuleViolationException("Limit Exceeded");
        }

        String periodKey = periodKeyOf(now, periodZone);
        VrpPayment payment = new VrpPayment(paymentId, consentId, command.tppId(), command.idempotencyKey(),
                instructed, periodKey, VrpPaymentStatus.ACCEPTED, now);
        VrpConsent next = new VrpConsent(consentId, tppId, psuId, limit, status, expiresAt,
                revokedAt, debtorAccountId, version + 1);
        MandatePaymentAccepted event = new MandatePaymentAccepted(UUID.randomUUID(), consentId, next.version(), now,
                payment.paymentId(), payment.tppId(), payment.amount(), payment.currency(), periodKey,
                periodTotal.amount());
        return new PaymentAuthorisation(next, payment, event);
    }


    /** Collection in the default (Asia/Dubai) limit period. */
    public PaymentAuthorisation authorisePayment(String paymentId,
                                                 SubmitVrpPaymentCommand command,
                                                 BigDecimal acceptedInPeriod,
                                                 Instant now) {
        return authorisePayment(paymentId, command, acceptedInPeriod, now, DEFAULT_LIMIT_PERIOD_ZONE);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
