package com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Row of sc_pay_recurring_mandates.mandate_record. Persistence shape only; the
 * domain VrpConsent is mapped to and from it by VrpPersistenceMapper. version
 * is the domain's optimistic-concurrency token, compared on update by
 * SpringDataVrpMandateRepository#compareAndSet (not a JPA @Version).
 */
@Entity
@Table(name = "mandate_record")
public class VrpMandateJpaEntity {

    @Id
    @Column(name = "consent_id", length = 64)
    private String consentId;

    @Column(name = "tpp_id", nullable = false, length = 128, updatable = false)
    private String tppId;

    @Column(name = "psu_id", nullable = false, length = 128, updatable = false)
    private String psuId;

    @Column(name = "max_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal maxAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "debtor_account_id", length = 64, updatable = false)
    private String debtorAccountId;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VrpMandateJpaEntity() {
    }

    public VrpMandateJpaEntity(String consentId, String tppId, String psuId, BigDecimal maxAmount, String currency,
                               String status, Instant expiresAt, Instant revokedAt, String debtorAccountId,
                               long version, Instant createdAt, Instant updatedAt) {
        this.consentId = consentId;
        this.tppId = tppId;
        this.psuId = psuId;
        this.maxAmount = maxAmount;
        this.currency = currency;
        this.status = status;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.debtorAccountId = debtorAccountId;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getConsentId() { return consentId; }
    public String getTppId() { return tppId; }
    public String getPsuId() { return psuId; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getDebtorAccountId() { return debtorAccountId; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
