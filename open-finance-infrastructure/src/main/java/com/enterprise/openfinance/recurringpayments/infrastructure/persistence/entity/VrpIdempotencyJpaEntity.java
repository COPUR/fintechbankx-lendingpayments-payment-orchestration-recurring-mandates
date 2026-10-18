package com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Row of sc_pay_recurring_mandates.mandate_idempotency_record, unique per (tpp_id, idempotency_key). */
@Entity
@Table(name = "mandate_idempotency_record")
public class VrpIdempotencyJpaEntity {

    @EmbeddedId
    private Key key;

    @Column(name = "request_hash", nullable = false, length = 256)
    private String requestHash;

    @Column(name = "payment_id", nullable = false, length = 64)
    private String paymentId;

    @Column(name = "payment_status", nullable = false, length = 16)
    private String paymentStatus;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected VrpIdempotencyJpaEntity() {
    }

    public VrpIdempotencyJpaEntity(Key key, String requestHash, String paymentId, String paymentStatus,
                                   Instant expiresAt, Instant createdAt) {
        this.key = key;
        this.requestHash = requestHash;
        this.paymentId = paymentId;
        this.paymentStatus = paymentStatus;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public Key getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public String getPaymentId() { return paymentId; }
    public String getPaymentStatus() { return paymentStatus; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "tpp_id", length = 128)
        private String tppId;

        @Column(name = "idempotency_key", length = 128)
        private String idempotencyKey;

        protected Key() {
        }

        public Key(String tppId, String idempotencyKey) {
            this.tppId = tppId;
            this.idempotencyKey = idempotencyKey;
        }

        public String getTppId() { return tppId; }
        public String getIdempotencyKey() { return idempotencyKey; }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that
                    && Objects.equals(tppId, that.tppId)
                    && Objects.equals(idempotencyKey, that.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tppId, idempotencyKey);
        }
    }
}
