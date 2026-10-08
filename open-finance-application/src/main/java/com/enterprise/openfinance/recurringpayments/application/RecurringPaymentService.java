package com.enterprise.openfinance.recurringpayments.application;

import com.enterprise.openfinance.recurringpayments.domain.command.CreateVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.RevokeVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.SubmitVrpPaymentCommand;
import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.recurringpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.recurringpayments.domain.model.DebtorAccount;
import com.enterprise.openfinance.recurringpayments.domain.model.MandateChange;
import com.enterprise.openfinance.recurringpayments.domain.model.PaymentAuthorisation;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpCollectionResult;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpIdempotencyRecord;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpSettings;
import com.enterprise.openfinance.recurringpayments.domain.port.in.RecurringPaymentUseCase;
import com.enterprise.openfinance.recurringpayments.domain.port.out.DebtorAccountPort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.MandateEventPublisher;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpCachePort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpConsentPort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpIdempotencyPort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpLockPort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpPaymentPort;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpConsentQuery;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpPaymentQuery;
import com.enterprise.openfinance.recurringpayments.domain.port.out.MandateTransactions;
import com.enterprise.openfinance.recurringpayments.domain.port.out.PsuConsentPort;
import com.enterprise.openfinance.recurringpayments.domain.model.PsuConsent;
import com.enterprise.openfinance.recurringpayments.domain.exception.MandateAlreadyExistsException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Mandate use cases. Each command loads the mandate, asks the aggregate for
 * the change, saves it and hands the aggregate's events to the outbox in the
 * same transaction. Collections run under a per-mandate lock so the monthly
 * total is read and extended by one request at a time.
 *
 * A mandate exists only under a consent the PSU authorised in the consent
 * service ({@link PsuConsentPort}); it takes that consent's id, PSU and
 * debtor account, and every collection re-checks the consent.
 *
 * Remote calls (consent, accounts) run first, outside any transaction and lock, so a
 * slow dependency never holds a database connection or blocks the mandate;
 * only then does {@link MandateTransactions} open the transaction.
 */
@Service
public class RecurringPaymentService implements RecurringPaymentUseCase {

    private final VrpConsentPort consentPort;
    private final VrpPaymentPort paymentPort;
    private final VrpIdempotencyPort idempotencyPort;
    private final VrpCachePort cachePort;
    private final VrpLockPort lockPort;
    private final MandateEventPublisher eventPublisher;
    private final DebtorAccountPort debtorAccountPort;
    private final PsuConsentPort psuConsentPort;
    private final MandateTransactions transactions;
    private final VrpSettings settings;
    private final Clock clock;

    public RecurringPaymentService(VrpConsentPort consentPort,
                                   VrpPaymentPort paymentPort,
                                   VrpIdempotencyPort idempotencyPort,
                                   VrpCachePort cachePort,
                                   VrpLockPort lockPort,
                                   MandateEventPublisher eventPublisher,
                                   DebtorAccountPort debtorAccountPort,
                                   PsuConsentPort psuConsentPort,
                                   MandateTransactions transactions,
                                   VrpSettings settings,
                                   Clock clock) {
        this.consentPort = consentPort;
        this.paymentPort = paymentPort;
        this.idempotencyPort = idempotencyPort;
        this.cachePort = cachePort;
        this.lockPort = lockPort;
        this.eventPublisher = eventPublisher;
        this.debtorAccountPort = debtorAccountPort;
        this.psuConsentPort = psuConsentPort;
        this.transactions = transactions;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public VrpConsent createConsent(CreateVrpConsentCommand command) {
        Instant now = Instant.now(clock);
        PsuConsent psuConsent = loadPsuConsent(command.consentId());
        MandateChange change = VrpConsent.authorise(psuConsent, command, now);
        verifyDebtorAccount(change.mandate().debtorAccountId(), change.mandate().currency());

        VrpConsent saved = transactions.inTransaction(() -> {
            if (consentPort.findById(change.mandate().consentId()).isPresent()) {
                throw new MandateAlreadyExistsException("A mandate already exists for this consent");
            }
            VrpConsent mandate = consentPort.save(change.mandate());
            eventPublisher.publish(change.events());
            return mandate;
        });

        cachePort.putConsent(consentCacheKey(saved.consentId(), saved.tppId()), saved, now.plus(settings.cacheTtl()));
        return saved;
    }

    @Override
    public Optional<VrpConsent> getConsent(GetVrpConsentQuery query) {
        Instant now = Instant.now(clock);
        String cacheKey = consentCacheKey(query.consentId(), query.tppId());

        Optional<VrpConsent> cached = cachePort.getConsent(cacheKey, now);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<VrpConsent> loaded = consentPort.findById(query.consentId())
                .map(consent -> {
                    consent.ensureOwnedBy(query.tppId());
                    return consent;
                });

        loaded.ifPresent(consent -> cachePort.putConsent(cacheKey, consent, now.plus(settings.cacheTtl())));
        return loaded;
    }

    @Override
    public void revokeConsent(RevokeVrpConsentCommand command) {
        Instant now = Instant.now(clock);
        VrpConsent consent = loadConsent(command.consentId());
        consent.ensureOwnedBy(command.tppId());

        // Under the mandate lock so a revocation cannot interleave with a collection.
        VrpConsent current = transactions.inTransaction(() -> lockPort.withConsentLock(command.consentId(), () -> {
            VrpConsent fresh = loadConsent(command.consentId());
            MandateChange change = fresh.revoke(now, command.reason());
            if (!change.changed()) {
                return fresh;
            }
            VrpConsent saved = consentPort.save(change.mandate());
            eventPublisher.publish(change.events());
            return saved;
        }));

        cachePort.putConsent(consentCacheKey(current.consentId(), current.tppId()), current,
                now.plus(settings.cacheTtl()));
    }

    @Override
    public VrpCollectionResult submitCollection(SubmitVrpPaymentCommand command) {
        Instant now = Instant.now(clock);

        VrpConsent consent = loadConsent(command.consentId());
        consent.ensureCanCollect(command, now);

        Optional<VrpCollectionResult> replay = lookupIdempotentReplay(command, now);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }

        // Remote checks before the transaction and the lock. The PSU may have withdrawn
        // the consent in the consent service; the debtor account of a mandate never
        // changes, so the locked re-read below cannot invalidate these checks.
        loadPsuConsent(consent.consentId()).ensureAuthorises(consent, now);
        if (consent.debtorAccountId() != null) {
            verifyDebtorAccount(consent.debtorAccountId(), consent.currency());
        }

        return transactions.inTransaction(() ->
                lockPort.withConsentLock(command.consentId(), () -> processCollectionLocked(command, now)));
    }

    @Override
    public Optional<VrpPayment> getPayment(GetVrpPaymentQuery query) {
        Instant now = Instant.now(clock);
        String cacheKey = paymentCacheKey(query.paymentId(), query.tppId());

        Optional<VrpPayment> cached = cachePort.getPayment(cacheKey, now);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<VrpPayment> loaded = paymentPort.findById(query.paymentId())
                .map(payment -> validatePaymentAccess(payment, query.tppId()));

        loaded.ifPresent(payment -> cachePort.putPayment(cacheKey, payment, now.plus(settings.cacheTtl())));
        return loaded;
    }

    private VrpCollectionResult processCollectionLocked(SubmitVrpPaymentCommand command, Instant now) {
        Optional<VrpCollectionResult> replay = lookupIdempotentReplay(command, now);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }

        // Re-read inside the lock: a revocation may have committed while we waited.
        VrpConsent consent = loadConsent(command.consentId());
        consent.ensureCanCollect(command, now);

        var acceptedInPeriod = paymentPort.sumAcceptedAmountByConsentAndPeriod(
                command.consentId(), VrpConsent.periodKeyOf(now));
        PaymentAuthorisation authorisation = consent.authorisePayment(
                "PAY-VRP-" + UUID.randomUUID(), command, acceptedInPeriod, now);

        VrpPayment saved = paymentPort.save(authorisation.payment());
        VrpConsent mandate = consentPort.save(authorisation.mandate());
        idempotencyPort.save(new VrpIdempotencyRecord(
                command.idempotencyKey(),
                command.tppId(),
                command.requestHash(),
                saved.paymentId(),
                saved.status(),
                now.plus(settings.idempotencyTtl())
        ));
        eventPublisher.publish(authorisation.events());

        cachePort.putConsent(consentCacheKey(mandate.consentId(), mandate.tppId()), mandate, now.plus(settings.cacheTtl()));
        cachePort.putPayment(paymentCacheKey(saved.paymentId(), saved.tppId()), saved, now.plus(settings.cacheTtl()));

        return new VrpCollectionResult(
                saved.paymentId(),
                saved.consentId(),
                saved.status(),
                command.interactionId(),
                saved.createdAt(),
                false
        );
    }

    private Optional<VrpCollectionResult> lookupIdempotentReplay(SubmitVrpPaymentCommand command, Instant now) {
        return idempotencyPort.find(command.idempotencyKey(), command.tppId(), now)
                .map(record -> {
                    if (!record.requestHash().equals(command.requestHash())) {
                        throw new IdempotencyConflictException("Idempotency conflict");
                    }

                    VrpPayment payment = paymentPort.findById(record.paymentId())
                            .orElseThrow(() -> new ResourceNotFoundException("Payment not found for idempotency record"));

                    return new VrpCollectionResult(
                            payment.paymentId(),
                            payment.consentId(),
                            payment.status(),
                            command.interactionId(),
                            payment.createdAt(),
                            true
                    );
                });
    }

    private void verifyDebtorAccount(String debtorAccountId, String currency) {
        DebtorAccount account = debtorAccountPort.findDebtorAccount(debtorAccountId)
                .orElseThrow(() -> new BusinessRuleViolationException("Debtor account not found"));
        account.ensureDebitableIn(currency);
    }

    private PsuConsent loadPsuConsent(String consentId) {
        return psuConsentPort.findConsent(consentId)
                .orElseThrow(() -> new ForbiddenException("Consent not found or not authorised"));
    }

    private VrpConsent loadConsent(String consentId) {
        return consentPort.findById(consentId)
                .orElseThrow(() -> new ResourceNotFoundException("Consent not found"));
    }

    private static VrpPayment validatePaymentAccess(VrpPayment payment, String tppId) {
        if (!payment.tppId().equals(tppId)) {
            throw new ForbiddenException("Consent participant mismatch");
        }
        return payment;
    }

    private static String consentCacheKey(String consentId, String tppId) {
        return "consent:" + consentId + ':' + tppId;
    }

    private static String paymentCacheKey(String paymentId, String tppId) {
        return "payment:" + paymentId + ':' + tppId;
    }
}
