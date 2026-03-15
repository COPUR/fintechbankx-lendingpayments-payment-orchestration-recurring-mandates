package com.enterprise.openfinance.recurringpayments.application;

import com.enterprise.openfinance.recurringpayments.domain.command.CreateVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.RevokeVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.SubmitVrpPaymentCommand;
import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.recurringpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpCollectionResult;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsentStatus;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpIdempotencyRecord;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPaymentStatus;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpSettings;
import com.enterprise.openfinance.recurringpayments.domain.port.in.RecurringPaymentUseCase;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpCachePort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpConsentPort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpIdempotencyPort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpLockPort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpPaymentPort;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpConsentQuery;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpPaymentQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class RecurringPaymentService implements RecurringPaymentUseCase {

    private final VrpConsentPort consentPort;
    private final VrpPaymentPort paymentPort;
    private final VrpIdempotencyPort idempotencyPort;
    private final VrpCachePort cachePort;
    private final VrpLockPort lockPort;
    private final VrpSettings settings;
    private final Clock clock;

    public RecurringPaymentService(VrpConsentPort consentPort,
                                   VrpPaymentPort paymentPort,
                                   VrpIdempotencyPort idempotencyPort,
                                   VrpCachePort cachePort,
                                   VrpLockPort lockPort,
                                   VrpSettings settings,
                                   Clock clock) {
        this.consentPort = consentPort;
        this.paymentPort = paymentPort;
        this.idempotencyPort = idempotencyPort;
        this.cachePort = cachePort;
        this.lockPort = lockPort;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public VrpConsent createConsent(CreateVrpConsentCommand command) {
        VrpConsent consent = new VrpConsent(
                "CONS-VRP-" + UUID.randomUUID(),
                command.tppId(),
                command.psuId(),
                command.maxAmount(),
                command.currency(),
                VrpConsentStatus.AUTHORISED,
                command.expiresAt(),
                null
        );

        VrpConsent saved = consentPort.save(consent);
        Instant now = Instant.now(clock);
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
                .map(consent -> validateConsentAccess(consent, query.tppId()));

        loaded.ifPresent(consent -> cachePort.putConsent(cacheKey, consent, now.plus(settings.cacheTtl())));
        return loaded;
    }

    @Override
    @Transactional
    public void revokeConsent(RevokeVrpConsentCommand command) {
        VrpConsent consent = consentPort.findById(command.consentId())
                .orElseThrow(() -> new ResourceNotFoundException("Consent not found"));
        ensureTppAccess(consent, command.tppId());

        VrpConsent revoked = consent.isRevoked() ? consent : consent.revoke(Instant.now(clock));
        consentPort.save(revoked);
        cachePort.putConsent(
                consentCacheKey(revoked.consentId(), revoked.tppId()),
                revoked,
                Instant.now(clock).plus(settings.cacheTtl())
        );
    }

    @Override
    @Transactional
    public VrpCollectionResult submitCollection(SubmitVrpPaymentCommand command) {
        Instant now = Instant.now(clock);

        VrpConsent consent = consentPort.findById(command.consentId())
                .orElseThrow(() -> new ResourceNotFoundException("Consent not found"));
        ensureTppAccess(consent, command.tppId());
        ensureConsentActive(consent, now);

        if (!consent.currency().equalsIgnoreCase(command.currency())) {
            throw new BusinessRuleViolationException("Currency mismatch");
        }

        Optional<VrpCollectionResult> replay = lookupIdempotentReplay(command, now);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }

        return lockPort.withConsentLock(command.consentId(), () -> processCollectionLocked(command, consent, now));
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

    private VrpCollectionResult processCollectionLocked(SubmitVrpPaymentCommand command,
                                                        VrpConsent consent,
                                                        Instant now) {
        Optional<VrpCollectionResult> replay = lookupIdempotentReplay(command, now);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }

        String periodKey = YearMonth.from(now.atZone(ZoneOffset.UTC)).toString();
        var consumedAmount = paymentPort.sumAcceptedAmountByConsentAndPeriod(command.consentId(), periodKey);
        var projected = consumedAmount.add(command.amount());

        if (projected.compareTo(consent.maxAmount()) > 0) {
            throw new BusinessRuleViolationException("Limit Exceeded");
        }

        VrpPayment payment = new VrpPayment(
                "PAY-VRP-" + UUID.randomUUID(),
                command.consentId(),
                command.tppId(),
                command.idempotencyKey(),
                command.amount(),
                command.currency(),
                periodKey,
                VrpPaymentStatus.ACCEPTED,
                now
        );

        VrpPayment saved = paymentPort.save(payment);

        VrpIdempotencyRecord record = new VrpIdempotencyRecord(
                command.idempotencyKey(),
                command.tppId(),
                command.requestHash(),
                saved.paymentId(),
                saved.status(),
                now.plus(settings.idempotencyTtl())
        );
        idempotencyPort.save(record);

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

    private VrpConsent validateConsentAccess(VrpConsent consent, String tppId) {
        ensureTppAccess(consent, tppId);
        return consent;
    }

    private static VrpPayment validatePaymentAccess(VrpPayment payment, String tppId) {
        if (!payment.tppId().equals(tppId)) {
            throw new ForbiddenException("Consent participant mismatch");
        }
        return payment;
    }

    private static void ensureTppAccess(VrpConsent consent, String tppId) {
        if (!consent.belongsToTpp(tppId)) {
            throw new ForbiddenException("Consent participant mismatch");
        }
    }

    private static void ensureConsentActive(VrpConsent consent, Instant now) {
        if (consent.isRevoked()) {
            throw new ForbiddenException("Consent Revoked");
        }
        if (!consent.isActive(now)) {
            throw new ForbiddenException("Consent expired");
        }
    }

    private static String consentCacheKey(String consentId, String tppId) {
        return "consent:" + consentId + ':' + tppId;
    }

    private static String paymentCacheKey(String paymentId, String tppId) {
        return "payment:" + paymentId + ':' + tppId;
    }
}
