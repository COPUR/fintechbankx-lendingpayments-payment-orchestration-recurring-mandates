package com.enterprise.openfinance.recurringpayments.infrastructure.persistence;

import com.enterprise.openfinance.recurringpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.recurringpayments.domain.exception.MandateVersionConflictException;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsentStatus;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpIdempotencyRecord;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPaymentStatus;
import com.enterprise.openfinance.recurringpayments.infrastructure.locking.PostgresAdvisoryVrpLockAdapter;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpIdempotencyJpaEntity;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpMandateJpaEntity;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpPaymentJpaEntity;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.mapper.VrpPersistenceMapper;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.repository.SpringDataVrpIdempotencyRepository;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.repository.SpringDataVrpMandateRepository;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.repository.SpringDataVrpPaymentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Adapter logic without a database; the SQL itself is exercised by RecurringMandatesServiceIT. */
class JpaAdaptersTest {

    private static final Instant NOW = Instant.parse("2026-02-09T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final SpringDataVrpMandateRepository mandates = mock(SpringDataVrpMandateRepository.class);
    private final SpringDataVrpPaymentRepository payments = mock(SpringDataVrpPaymentRepository.class);
    private final SpringDataVrpIdempotencyRepository idempotency = mock(SpringDataVrpIdempotencyRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    @Test
    void newMandateIsInsertedAndFlushed() {
        JpaVrpConsentAdapter adapter = new JpaVrpConsentAdapter(mandates, entityManager, CLOCK);
        VrpConsent mandate = mandate(0L, VrpConsentStatus.AUTHORISED);

        assertThat(adapter.save(mandate)).isSameAs(mandate);

        ArgumentCaptor<VrpMandateJpaEntity> persisted = ArgumentCaptor.forClass(VrpMandateJpaEntity.class);
        InOrder order = inOrder(entityManager);
        order.verify(entityManager).persist(persisted.capture());
        order.verify(entityManager).flush();
        assertThat(persisted.getValue().getVersion()).isZero();
        assertThat(persisted.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void changedMandateIsACompareAndSetOnThePreviousVersion() {
        JpaVrpConsentAdapter adapter = new JpaVrpConsentAdapter(mandates, entityManager, CLOCK);
        VrpConsent revoked = mandate(3L, VrpConsentStatus.REVOKED);
        when(mandates.compareAndSet("CONS-1", "REVOKED", NOW, 3L, 2L, NOW)).thenReturn(1);

        assertThat(adapter.save(revoked)).isSameAs(revoked);

        when(mandates.compareAndSet(anyString(), anyString(), any(), anyLong(), anyLong(), any())).thenReturn(0);
        assertThatThrownBy(() -> adapter.save(revoked))
                .isInstanceOf(MandateVersionConflictException.class)
                .hasMessageContaining("expected version 2");
    }

    @Test
    void readsAreDetachedSoAReReadSeesTheDatabase() {
        JpaVrpConsentAdapter adapter = new JpaVrpConsentAdapter(mandates, entityManager, CLOCK);
        VrpMandateJpaEntity entity = VrpPersistenceMapper.toEntity(mandate(1L, VrpConsentStatus.REVOKED), NOW);
        when(mandates.findById("CONS-1")).thenReturn(Optional.of(entity));

        assertThat(adapter.findById("CONS-1")).hasValueSatisfying(m -> assertThat(m.version()).isEqualTo(1L));
        verify(entityManager).detach(entity);
        assertThat(adapter.findById("CONS-404")).isEmpty();
    }

    @Test
    void paymentsAreInsertedReadAndSummed() {
        JpaVrpPaymentAdapter adapter = new JpaVrpPaymentAdapter(payments, entityManager);
        VrpPayment payment = new VrpPayment("PAY-1", "CONS-1", "TPP-001", "IDEMP-1", new BigDecimal("10.00"), "AED",
                "2026-02", VrpPaymentStatus.ACCEPTED, NOW);

        assertThat(adapter.save(payment)).isSameAs(payment);
        verify(entityManager).persist(any(VrpPaymentJpaEntity.class));
        verify(entityManager).flush();

        when(payments.findById("PAY-1")).thenReturn(Optional.of(VrpPersistenceMapper.toEntity(payment)));
        assertThat(adapter.findById("PAY-1")).contains(payment);

        when(payments.sumAccepted("CONS-1", "2026-02")).thenReturn(new BigDecimal("4000.0000"));
        assertThat(adapter.sumAcceptedAmountByConsentAndPeriod("CONS-1", "2026-02")).isEqualByComparingTo("4000.00");
    }

    @Test
    void idempotencyRecordsIgnoreExpiredRowsAndAnActiveClaimIsAConflict() {
        JpaVrpIdempotencyAdapter adapter = new JpaVrpIdempotencyAdapter(idempotency, CLOCK);
        VrpIdempotencyJpaEntity.Key key = new VrpIdempotencyJpaEntity.Key("TPP-001", "IDEMP-1");
        when(idempotency.findById(key)).thenReturn(Optional.of(new VrpIdempotencyJpaEntity(key, "h", "PAY-1", "ACCEPTED",
                NOW.plusSeconds(60), NOW)));

        assertThat(adapter.find("IDEMP-1", "TPP-001", NOW)).isPresent();
        assertThat(adapter.find("IDEMP-1", "TPP-001", NOW.plusSeconds(60))).isEmpty();

        VrpIdempotencyRecord record = new VrpIdempotencyRecord("IDEMP-1", "TPP-001", "h", "PAY-1",
                VrpPaymentStatus.ACCEPTED, NOW.plusSeconds(60));
        when(idempotency.claim("TPP-001", "IDEMP-1", "h", "PAY-1", "ACCEPTED", NOW.plusSeconds(60), NOW)).thenReturn(1);
        adapter.save(record);

        when(idempotency.claim(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> adapter.save(record)).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void lockAdapterTakesTheMandateAdvisoryLockBeforeRunningTheOperation() {
        PostgresAdvisoryVrpLockAdapter lock = new PostgresAdvisoryVrpLockAdapter(mandates);

        String result = lock.withConsentLock("CONS-1", () -> {
            verify(mandates).lockForTransaction("vrp-mandate:CONS-1");
            return "done";
        });

        assertThat(result).isEqualTo("done");
    }

    @Test
    void purgeDeletesExpiredIdempotencyRecords() {
        when(idempotency.deleteExpired(eq(NOW))).thenReturn(3);

        assertThat(new IdempotencyRecordPurge(idempotency, CLOCK).purgeExpired()).isEqualTo(3);
    }

    private static VrpConsent mandate(long version, VrpConsentStatus status) {
        return new VrpConsent("CONS-1", "TPP-001", "PSU-001", new BigDecimal("5000.00"), "AED", status,
                Instant.parse("2099-01-01T00:00:00Z"), status == VrpConsentStatus.REVOKED ? NOW : null, null, version);
    }
}
