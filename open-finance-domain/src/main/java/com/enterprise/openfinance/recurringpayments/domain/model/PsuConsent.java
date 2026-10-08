package com.enterprise.openfinance.recurringpayments.domain.model;

import com.enterprise.openfinance.recurringpayments.domain.command.CreateVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ConsentNotUsableException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ConsentNotUsableException.Reason;
import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Local read model of a consent owned by the consent service
 * (fintechbankx-openfinance-consent-auth-service), read through
 * {@code PsuConsentPort}. The PSU authorised it there; {@code usable} is the
 * consent service's authoritative flag (AUTHORIZED and not expired). This
 * service never stores or changes consents.
 */
public record PsuConsent(
        String consentId,
        String participantId,
        String customerId,
        Set<String> scopes,
        Set<String> accountIds,
        Instant expiresAt,
        boolean usable
) {

    /** Consent-service scope that allows variable recurring payments. */
    public static final String VRP_SCOPE = "INITIATEVRP";

    public PsuConsent {
        if (isBlank(consentId)) {
            throw new IllegalArgumentException("consentId is required");
        }
        if (isBlank(participantId)) {
            throw new IllegalArgumentException("participantId is required");
        }
        if (isBlank(customerId)) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }
        consentId = consentId.trim();
        participantId = participantId.trim();
        customerId = customerId.trim();
        scopes = scopes == null ? Set.of() : scopes.stream().map(scope -> scope.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        accountIds = accountIds == null ? Set.of() : accountIds.stream().map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The terms a mandate may have under this consent. Values the request
     * states must match the consent; omitted ones are taken from it.
     */
    public MandateTerms termsFor(CreateVrpConsentCommand command, Instant now) {
        ensureUsableBy(command.tppId(), now);
        if (command.psuId() != null && !command.psuId().equals(customerId)) {
            throw new ForbiddenException("PsuId does not match the consent");
        }
        Instant expiry = command.expiresAt() == null ? expiresAt : command.expiresAt();
        if (expiry.isAfter(expiresAt)) {
            throw new ForbiddenException("ExpiryDateTime is after the consent expiry");
        }
        return new MandateTerms(consentId, participantId, customerId, debtorAccount(command.debtorAccountId()), expiry);
    }

    /** Before each collection: the consent still authorises this mandate. */
    public void ensureAuthorises(VrpConsent mandate, Instant now) {
        ensureUsableBy(mandate.tppId(), now);
        if (mandate.debtorAccountId() != null && !accountIds.contains(mandate.debtorAccountId())) {
            throw new ForbiddenException("DebtorAccount is not covered by the consent");
        }
    }

    /**
     * Shared by mandate creation and every collection: the PSU authorised the
     * consent, it has not expired, it is this TPP's and it grants VRP. Every
     * refusal is the same {@link ConsentNotUsableException}; only its reason differs.
     */
    private void ensureUsableBy(String tppId, Instant now) {
        if (!usable) {
            throw new ConsentNotUsableException(Reason.NOT_AUTHORISED);
        }
        if (!expiresAt.isAfter(now)) {
            throw new ConsentNotUsableException(Reason.EXPIRED);
        }
        if (!participantId.equals(tppId)) {
            throw new ConsentNotUsableException(Reason.OTHER_TPP);
        }
        if (!scopes.contains(VRP_SCOPE)) {
            throw new ConsentNotUsableException(Reason.MISSING_SCOPE);
        }
    }

    private String debtorAccount(String requested) {
        if (requested != null) {
            if (!accountIds.contains(requested)) {
                throw DebtorAccount.notUsable(); // same answer as an unknown account: no enumeration
            }
            return requested;
        }
        if (accountIds.isEmpty()) {
            throw new ForbiddenException("Consent covers no debtor account");
        }
        if (accountIds.size() > 1) {
            throw new BusinessRuleViolationException(
                    "DebtorAccount.Identification is required: the consent covers more than one account");
        }
        return accountIds.iterator().next();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
