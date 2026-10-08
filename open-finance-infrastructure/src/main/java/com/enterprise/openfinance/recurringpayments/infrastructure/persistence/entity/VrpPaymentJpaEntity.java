package com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Row of sc_pay_recurring_mandates.vrp_payment. Payments are written once and never updated. */
@Entity
@Table(name = "vrp_payment")
public class VrpPaymentJpaEntity {

    @Id
    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "consent_id", nullable = false, length = 64, updatable = false)
    private String consentId;

    @Column(name = "tpp_id", nullable = false, length = 128, updatable = false)
    private String tppId;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "period_key", nullable = false, length = 7, updatable = false)
    private String periodKey;

    @Column(name = "status", nullable = false, length = 16, updatable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VrpPaymentJpaEntity() {
    }

    public VrpPaymentJpaEntity(String paymentId, String consentId, String tppId, String idempotencyKey,
                               BigDecimal amount, String currency, String periodKey, String status,
                               Instant createdAt) {
        this.paymentId = paymentId;
        this.consentId = consentId;
        this.tppId = tppId;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = currency;
        this.periodKey = periodKey;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getPaymentId() { return paymentId; }
    public String getConsentId() { return consentId; }
    public String getTppId() { return tppId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPeriodKey() { return periodKey; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
