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
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpCachePort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpConsentPort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpIdempotencyPort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpLockPort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpPaymentPort;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpConsentQuery;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpPaymentQuery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class RecurringPaymentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-09T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldCreateAuthorisedConsent() {
        TestConsentPort consentPort = new TestConsentPort();
        RecurringPaymentService service = service(consentPort, new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), new TestLockPort());

        VrpConsent consent = service.createConsent(new CreateVrpConsentCommand(
                "TPP-001",
                "PSU-001",
                new BigDecimal("5000.00"),
                "AED",
                Instant.parse("2099-01-01T00:00:00Z"),
                "ix-1"
        ));

        assertThat(consent.status()).isEqualTo(VrpConsentStatus.AUTHORISED);
        assertThat(consent.consentId()).isNotBlank();
        assertThat(consentPort.data).containsKey(consent.consentId());
    }

    @Test
    void shouldSubmitPaymentWithinLimit() {
        TestConsentPort consentPort = new TestConsentPort();
        TestPaymentPort paymentPort = new TestPaymentPort();
        TestIdempotencyPort idempotencyPort = new TestIdempotencyPort();

        RecurringPaymentService service = service(consentPort, paymentPort, idempotencyPort, new TestCachePort(), new TestLockPort());
        VrpConsent consent = createConsent(service);

        VrpCollectionResult result = service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                consent.consentId(),
                "IDEMP-001",
                new BigDecimal("100.00"),
                "AED",
                "ix-2"
        ));

        assertThat(result.status()).isEqualTo(VrpPaymentStatus.ACCEPTED);
        assertThat(result.idempotencyReplay()).isFalse();
        assertThat(paymentPort.saveCount.get()).isEqualTo(1);
        assertThat(idempotencyPort.records).hasSize(1);
    }

    @Test
    void shouldReturnIdempotencyReplayForSamePayload() {
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), new TestLockPort());
        VrpConsent consent = createConsent(service);

        VrpCollectionResult first = service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                consent.consentId(),
                "IDEMP-001",
                new BigDecimal("100.00"),
                "AED",
                "ix-3"
        ));

        VrpCollectionResult replay = service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                consent.consentId(),
                "IDEMP-001",
                new BigDecimal("100.00"),
                "AED",
                "ix-3"
        ));

        assertThat(first.idempotencyReplay()).isFalse();
        assertThat(replay.idempotencyReplay()).isTrue();
        assertThat(replay.paymentId()).isEqualTo(first.paymentId());
    }

    @Test
    void shouldRejectIdempotencyConflictForDifferentPayload() {
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), new TestLockPort());
        VrpConsent consent = createConsent(service);

        service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                consent.consentId(),
                "IDEMP-001",
                new BigDecimal("100.00"),
                "AED",
                "ix-4"
        ));

        assertThatThrownBy(() -> service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                consent.consentId(),
                "IDEMP-001",
                new BigDecimal("101.00"),
                "AED",
                "ix-4"
        ))).isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Idempotency conflict");
    }

    @Test
    void shouldRejectWhenLimitExceeded() {
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), new TestLockPort());
        VrpConsent consent = createConsent(service);

        assertThatThrownBy(() -> service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                consent.consentId(),
                "IDEMP-001",
                new BigDecimal("5001.00"),
                "AED",
                "ix-5"
        ))).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Limit Exceeded");
    }

    @Test
    void shouldEnforceCumulativeLimit() {
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), new TestLockPort());
        VrpConsent consent = createConsent(service);

        for (int i = 1; i <= 4; i++) {
            VrpCollectionResult result = service.submitCollection(new SubmitVrpPaymentCommand(
                    "TPP-001",
                    consent.consentId(),
                    "IDEMP-CUM-" + i,
                    new BigDecimal("1001.00"),
                    "AED",
                    "ix-cum"
            ));
            assertThat(result.status()).isEqualTo(VrpPaymentStatus.ACCEPTED);
        }

        assertThatThrownBy(() -> service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                consent.consentId(),
                "IDEMP-CUM-5",
                new BigDecimal("1001.00"),
                "AED",
                "ix-cum"
        ))).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Limit Exceeded");
    }

    @Test
    void shouldRejectWhenConsentRevoked() {
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), new TestLockPort());
        VrpConsent consent = createConsent(service);

        service.revokeConsent(new RevokeVrpConsentCommand(consent.consentId(), "TPP-001", "ix-6", "User request"));

        assertThatThrownBy(() -> service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                consent.consentId(),
                "IDEMP-REV-1",
                new BigDecimal("10.00"),
                "AED",
                "ix-6"
        ))).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Consent Revoked");
    }

    @Test
    void shouldServeConsentFromCacheAfterFirstLoad() {
        TestConsentPort consentPort = new TestConsentPort();
        TestCachePort cachePort = new TestCachePort();
        RecurringPaymentService service = service(consentPort, new TestPaymentPort(), new TestIdempotencyPort(), cachePort, new TestLockPort());

        VrpConsent consent = createConsent(service);
        assertThat(service.getConsent(new GetVrpConsentQuery(consent.consentId(), "TPP-001", "ix-7"))).isPresent();
        assertThat(service.getConsent(new GetVrpConsentQuery(consent.consentId(), "TPP-001", "ix-7"))).isPresent();

        assertThat(cachePort.consentCache).isNotEmpty();
    }

    @Test
    void shouldAllowOnlyOneConcurrentPaymentWhenCombinedAmountExceedsLimit() throws Exception {
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), new TestLockPort());
        VrpConsent consent = createConsent(service);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Boolean> taskA = paymentTask(service, consent.consentId(), "IDEMP-RACE-A", ready, start);
        Callable<Boolean> taskB = paymentTask(service, consent.consentId(), "IDEMP-RACE-B", ready, start);

        Future<Boolean> f1 = executor.submit(taskA);
        Future<Boolean> f2 = executor.submit(taskB);

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        boolean success1 = f1.get(5, TimeUnit.SECONDS);
        boolean success2 = f2.get(5, TimeUnit.SECONDS);

        executor.shutdownNow();

        assertThat(List.of(success1, success2)).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void shouldReturnEmptyWhenConsentOrPaymentMissing() {
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), new TestLockPort());

        assertThat(service.getConsent(new GetVrpConsentQuery("CONS-404", "TPP-001", "ix-8"))).isEmpty();
        assertThat(service.getPayment(new GetVrpPaymentQuery("PAY-404", "TPP-001", "ix-8"))).isEmpty();
    }

    @Test
    void shouldRejectConsentAndPaymentLookupForDifferentTpp() {
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), new TestLockPort());
        VrpConsent consent = createConsent(service);
        VrpCollectionResult result = service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                consent.consentId(),
                "IDEMP-LOOKUP-1",
                new BigDecimal("50.00"),
                "AED",
                "ix-9"
        ));

        assertThatThrownBy(() -> service.getConsent(new GetVrpConsentQuery(consent.consentId(), "TPP-OTHER", "ix-9")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("participant mismatch");

        assertThatThrownBy(() -> service.getPayment(new GetVrpPaymentQuery(result.paymentId(), "TPP-OTHER", "ix-9")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("participant mismatch");
    }

    @Test
    void shouldRejectRevokeWhenConsentMissingOrNotOwnedByTpp() {
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), new TestLockPort());
        VrpConsent consent = createConsent(service);

        assertThatThrownBy(() -> service.revokeConsent(new RevokeVrpConsentCommand("CONS-404", "TPP-001", "ix-10", "missing")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Consent not found");

        assertThatThrownBy(() -> service.revokeConsent(new RevokeVrpConsentCommand(consent.consentId(), "TPP-OTHER", "ix-10", "forbidden")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("participant mismatch");
    }

    @Test
    void shouldRejectSubmissionWhenConsentMissingExpiredOrCurrencyMismatch() {
        TestConsentPort consentPort = new TestConsentPort();
        RecurringPaymentService service = service(consentPort, new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), new TestLockPort());

        assertThatThrownBy(() -> service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                "CONS-404",
                "IDEMP-MISS",
                new BigDecimal("10.00"),
                "AED",
                "ix-11"
        ))).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Consent not found");

        VrpConsent expired = new VrpConsent(
                "CONS-EXP-001",
                "TPP-001",
                "PSU-001",
                new BigDecimal("5000.00"),
                "AED",
                VrpConsentStatus.AUTHORISED,
                Instant.parse("2026-02-08T00:00:00Z"),
                null
        );
        consentPort.save(expired);

        assertThatThrownBy(() -> service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                expired.consentId(),
                "IDEMP-EXP",
                new BigDecimal("10.00"),
                "AED",
                "ix-11"
        ))).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("expired");

        VrpConsent active = createConsent(service);
        assertThatThrownBy(() -> service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                active.consentId(),
                "IDEMP-CUR",
                new BigDecimal("10.00"),
                "USD",
                "ix-11"
        ))).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void shouldFailReplayWhenIdempotencyRecordReferencesMissingPayment() {
        TestIdempotencyPort idempotencyPort = new TestIdempotencyPort();
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), idempotencyPort, new TestCachePort(), new TestLockPort());
        VrpConsent consent = createConsent(service);

        idempotencyPort.save(new VrpIdempotencyRecord(
                "IDEMP-ORPHAN",
                "TPP-001",
                consent.consentId() + "|10.00|AED",
                "PAY-404",
                VrpPaymentStatus.ACCEPTED,
                Instant.parse("2026-02-10T00:00:00Z")
        ));

        assertThatThrownBy(() -> service.submitCollection(new SubmitVrpPaymentCommand(
                "TPP-001",
                consent.consentId(),
                "IDEMP-ORPHAN",
                new BigDecimal("10.00"),
                "AED",
                "ix-12"
        ))).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Payment not found");
    }

    private static Callable<Boolean> paymentTask(RecurringPaymentService service,
                                                 String consentId,
                                                 String idemKey,
                                                 CountDownLatch ready,
                                                 CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                service.submitCollection(new SubmitVrpPaymentCommand(
                        "TPP-001",
                        consentId,
                        idemKey,
                        new BigDecimal("3000.00"),
                        "AED",
                        "ix-race"
                ));
                return true;
            } catch (RuntimeException ex) {
                return false;
            }
        };
    }

    private static VrpConsent createConsent(RecurringPaymentService service) {
        return service.createConsent(new CreateVrpConsentCommand(
                "TPP-001",
                "PSU-001",
                new BigDecimal("5000.00"),
                "AED",
                Instant.parse("2099-01-01T00:00:00Z"),
                "ix-create"
        ));
    }

    private static RecurringPaymentService service(
            VrpConsentPort consentPort,
            VrpPaymentPort paymentPort,
            VrpIdempotencyPort idempotencyPort,
            VrpCachePort cachePort,
            VrpLockPort lockPort
    ) {
        return new RecurringPaymentService(
                consentPort,
                paymentPort,
                idempotencyPort,
                cachePort,
                lockPort,
                new VrpSettings(Duration.ofHours(24), Duration.ofSeconds(30)),
                CLOCK
        );
    }

    private static final class TestConsentPort implements VrpConsentPort {
        private final Map<String, VrpConsent> data = new ConcurrentHashMap<>();

        @Override
        public VrpConsent save(VrpConsent consent) {
            data.put(consent.consentId(), consent);
            return consent;
        }

        @Override
        public Optional<VrpConsent> findById(String consentId) {
            return Optional.ofNullable(data.get(consentId));
        }
    }

    private static final class TestPaymentPort implements VrpPaymentPort {
        private final Map<String, VrpPayment> data = new ConcurrentHashMap<>();
        private final AtomicInteger saveCount = new AtomicInteger();

        @Override
        public VrpPayment save(VrpPayment payment) {
            saveCount.incrementAndGet();
            data.put(payment.paymentId(), payment);
            return payment;
        }

        @Override
        public Optional<VrpPayment> findById(String paymentId) {
            return Optional.ofNullable(data.get(paymentId));
        }

        @Override
        public BigDecimal sumAcceptedAmountByConsentAndPeriod(String consentId, String periodKey) {
            return data.values().stream()
                    .filter(VrpPayment::isAccepted)
                    .filter(payment -> payment.consentId().equals(consentId))
                    .filter(payment -> payment.periodKey().equals(periodKey))
                    .map(VrpPayment::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private static final class TestIdempotencyPort implements VrpIdempotencyPort {
        private final Map<String, VrpIdempotencyRecord> records = new ConcurrentHashMap<>();

        @Override
        public Optional<VrpIdempotencyRecord> find(String idempotencyKey, String tppId, Instant now) {
            String key = idempotencyKey + ':' + tppId;
            VrpIdempotencyRecord record = records.get(key);
            if (record == null || !record.isActive(now)) {
                records.remove(key);
                return Optional.empty();
            }
            return Optional.of(record);
        }

        @Override
        public void save(VrpIdempotencyRecord record) {
            records.put(record.idempotencyKey() + ':' + record.tppId(), record);
        }
    }

    private static final class TestCachePort implements VrpCachePort {
        private final Map<String, CacheItem<VrpConsent>> consentCache = new ConcurrentHashMap<>();
        private final Map<String, CacheItem<VrpPayment>> paymentCache = new ConcurrentHashMap<>();

        @Override
        public Optional<VrpConsent> getConsent(String key, Instant now) {
            CacheItem<VrpConsent> item = consentCache.get(key);
            if (item == null || !item.expiresAt.isAfter(now)) {
                consentCache.remove(key);
                return Optional.empty();
            }
            return Optional.of(item.value);
        }

        @Override
        public void putConsent(String key, VrpConsent consent, Instant expiresAt) {
            consentCache.put(key, new CacheItem<>(consent, expiresAt));
        }

        @Override
        public Optional<VrpPayment> getPayment(String key, Instant now) {
            CacheItem<VrpPayment> item = paymentCache.get(key);
            if (item == null || !item.expiresAt.isAfter(now)) {
                paymentCache.remove(key);
                return Optional.empty();
            }
            return Optional.of(item.value);
        }

        @Override
        public void putPayment(String key, VrpPayment payment, Instant expiresAt) {
            paymentCache.put(key, new CacheItem<>(payment, expiresAt));
        }

        private static final class CacheItem<T> {
            private final T value;
            private final Instant expiresAt;

            private CacheItem(T value, Instant expiresAt) {
                this.value = value;
                this.expiresAt = expiresAt;
            }
        }
    }

    private static final class TestLockPort implements VrpLockPort {
        private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

        @Override
        public <T> T withConsentLock(String consentId, java.util.function.Supplier<T> operation) {
            ReentrantLock lock = locks.computeIfAbsent(consentId, key -> new ReentrantLock());
            lock.lock();
            try {
                return operation.get();
            } finally {
                lock.unlock();
            }
        }
    }
}
