package com.enterprise.openfinance.recurringpayments.infrastructure.persistence.mapper;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsentStatus;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpIdempotencyRecord;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPaymentStatus;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpIdempotencyJpaEntity;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpMandateJpaEntity;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpPaymentJpaEntity;

import java.time.Instant;

/** Maps the mandate domain records to their tables and back. Enum values are stored by name. */
public final class VrpPersistenceMapper {

    private VrpPersistenceMapper() {
    }

    public static VrpMandateJpaEntity toEntity(VrpConsent consent, Instant now) {
        return new VrpMandateJpaEntity(consent.consentId(), consent.tppId(), consent.psuId(), consent.maxAmount(),
                consent.currency(), consent.status().name(), consent.expiresAt(), consent.revokedAt(),
                consent.debtorAccountId(), consent.version(), now, now);
    }

    public static VrpConsent toDomain(VrpMandateJpaEntity entity) {
        return new VrpConsent(entity.getConsentId(), entity.getTppId(), entity.getPsuId(), entity.getMaxAmount(),
                entity.getCurrency(), VrpConsentStatus.valueOf(entity.getStatus()), entity.getExpiresAt(),
                entity.getRevokedAt(), entity.getDebtorAccountId(), entity.getVersion());
    }

    public static VrpPaymentJpaEntity toEntity(VrpPayment payment) {
        return new VrpPaymentJpaEntity(payment.paymentId(), payment.consentId(), payment.tppId(),
                payment.idempotencyKey(), payment.amount(), payment.currency(), payment.periodKey(),
                payment.status().name(), payment.createdAt());
    }

    public static VrpPayment toDomain(VrpPaymentJpaEntity entity) {
        return new VrpPayment(entity.getPaymentId(), entity.getConsentId(), entity.getTppId(),
                entity.getIdempotencyKey(), entity.getAmount(), entity.getCurrency(), entity.getPeriodKey(),
                VrpPaymentStatus.valueOf(entity.getStatus()), entity.getCreatedAt());
    }

    public static VrpIdempotencyRecord toDomain(VrpIdempotencyJpaEntity entity) {
        return new VrpIdempotencyRecord(entity.getKey().getIdempotencyKey(), entity.getKey().getTppId(),
                entity.getRequestHash(), entity.getPaymentId(), VrpPaymentStatus.valueOf(entity.getPaymentStatus()),
                entity.getExpiresAt());
    }
}
