package com.enterprise.openfinance.recurringpayments.application;

import com.enterprise.openfinance.recurringpayments.domain.command.CreateVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.RevokeVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.SubmitVrpPaymentCommand;
import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.recurringpayments.domain.exception.MandateAlreadyExistsException;
import com.enterprise.openfinance.recurringpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpCollectionResult;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsentStatus;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpIdempotencyRecord;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPaymentStatus;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpSettings;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateCreated;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateEvent;
import com.enterprise.openfinance.recurringpayments.domain.event.MandatePaymentAccepted;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateRevoked;
import com.enterprise.openfinance.recurringpayments.domain.model.DebtorAccount;
import com.enterprise.openfinance.recurringpayments.domain.port.out.DebtorAccountPort;
import com.enterprise.openfinance.recurringpayments.domain.port.out.MandateTransactions;
import com.enterprise.openfinance.recurringpayments.domain.port.out.PsuConsentPort;
import com.enterprise.openfinance.recurringpayments.domain.model.PsuConsent;
import com.enterprise.openfinance.recurringpayments.domain.port.out.MandateEventPublisher;
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
    private static final CountingTransactions TRANSACTIONS = new CountingTransactions();

    /** Counts open transactions per thread, like a thread-bound Spring transaction. */
    private static final class CountingTransactions implements MandateTransactions {
        private final ThreadLocal<Integer> open = ThreadLocal.withInitial(() -> 0);

        @Override
        public <T> T inTransaction(java.util.function.Supplier<T> work) {
            open.set(open.get() + 1);
            try {
                return work.get();
            } finally {
                open.set(open.get() - 1);
            }
        }

        boolean active() {
            return open.get() > 0;
        }
    }

    @Test
    void shouldCreateAuthorisedConsent() {
        TestConsentPort consentPort = new TestConsentPort();
        RecurringPaymentService service = service(consentPort, new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), new TestLockPort());

        VrpConsent consent = service.createConsent(new CreateVrpConsentCommand(
                "TPP-001",
                "CONS-AUTH-CREATE",
                "PSU-001",
                new BigDecimal("5000.00"),
                "AED",
                Instant.parse("2099-01-01T00:00:00Z"),
                "ix-1",
                "ACC-DEFAULT"
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

    @Test
    void shouldPublishCreatedRevokedAndPaymentAcceptedEventsWithIncreasingMandateVersions() {
        TestConsentPort consentPort = new TestConsentPort();
        RecordingEventPublisher events = new RecordingEventPublisher();
        RecurringPaymentService service = service(consentPort, new TestPaymentPort(), new TestIdempotencyPort(),
                new TestCachePort(), new TestLockPort(), events, new TestDebtorAccountPort());

        VrpConsent consent = createConsent(service);
        service.submitCollection(new SubmitVrpPaymentCommand("TPP-001", consent.consentId(), "IDEMP-EV-1",
                new BigDecimal("1250.00"), "AED", "ix-ev"));
        service.submitCollection(new SubmitVrpPaymentCommand("TPP-001", consent.consentId(), "IDEMP-EV-2",
                new BigDecimal("750.00"), "AED", "ix-ev"));
        service.revokeConsent(new RevokeVrpConsentCommand(consent.consentId(), "TPP-001", "ix-ev", "Customer request"));
        service.revokeConsent(new RevokeVrpConsentCommand(consent.consentId(), "TPP-001", "ix-ev", "Again"));

        assertThat(events.published).extracting(e -> e.getClass().getSimpleName()).containsExactly(
                "MandateCreated", "MandatePaymentAccepted", "MandatePaymentAccepted", "MandateRevoked");
        assertThat(events.published).extracting(MandateEvent::aggregateVersion).containsExactly(0L, 1L, 2L, 3L);
        assertThat(((MandatePaymentAccepted) events.published.get(2)).periodTotal()).isEqualByComparingTo("2000.00");
        assertThat(((MandateRevoked) events.published.get(3)).reason()).isEqualTo("Customer request");
        assertThat(consentPort.data.get(consent.consentId()).version()).isEqualTo(3L);
    }

    @Test
    void shouldNotPublishAnythingForAnIdempotentReplayOrARejectedPayment() {
        RecordingEventPublisher events = new RecordingEventPublisher();
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), new TestIdempotencyPort(),
                new TestCachePort(), new TestLockPort(), events, new TestDebtorAccountPort());
        VrpConsent consent = createConsent(service);
        SubmitVrpPaymentCommand payment = new SubmitVrpPaymentCommand("TPP-001", consent.consentId(), "IDEMP-RP-1",
                new BigDecimal("100.00"), "AED", "ix-rp");

        service.submitCollection(payment);
        service.submitCollection(payment);
        assertThatThrownBy(() -> service.submitCollection(new SubmitVrpPaymentCommand("TPP-001", consent.consentId(),
                "IDEMP-RP-2", new BigDecimal("4900.01"), "AED", "ix-rp")))
                .isInstanceOf(BusinessRuleViolationException.class);

        assertThat(events.published).hasSize(2);
        assertThat(events.published.get(0)).isInstanceOf(MandateCreated.class);
    }

    @Test
    void shouldVerifyTheDebtorAccountWhenTheMandateNamesOne() {
        TestDebtorAccountPort accounts = new TestDebtorAccountPort();
        accounts.accounts.put("ACC-ACTIVE", new DebtorAccount("ACC-ACTIVE", true, true, "AED"));
        accounts.accounts.put("ACC-BLOCKED", new DebtorAccount("ACC-BLOCKED", false, true, "AED"));
        RecordingEventPublisher events = new RecordingEventPublisher();
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), new TestIdempotencyPort(),
                new TestCachePort(), new TestLockPort(), events, accounts);

        VrpConsent linked = service.createConsent(consentCommand("ACC-ACTIVE"));
        assertThat(linked.debtorAccountId()).isEqualTo("ACC-ACTIVE");

        assertThatThrownBy(() -> service.createConsent(consentCommand("ACC-UNKNOWN")))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Debtor account not found");
        assertThatThrownBy(() -> service.createConsent(consentCommand("ACC-BLOCKED")))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Debtor account is not active");

        // The account is blocked after the mandate was set up: the next collection is refused.
        accounts.accounts.put("ACC-ACTIVE", new DebtorAccount("ACC-ACTIVE", true, false, "AED"));
        assertThatThrownBy(() -> service.submitCollection(new SubmitVrpPaymentCommand("TPP-001", linked.consentId(),
                "IDEMP-DA-1", new BigDecimal("10.00"), "AED", "ix-da")))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Debtor account does not allow debits");
        assertThat(events.published).hasSize(1);
    }

    @Test
    void shouldRejectACollectionWhenTheMandateWasRevokedWhileWaitingForTheLock() {
        TestConsentPort consentPort = new TestConsentPort();
        RecurringPaymentService[] holder = new RecurringPaymentService[1];
        VrpLockPort revokingLock = new VrpLockPort() {
            @Override
            public <T> T withConsentLock(String consentId, java.util.function.Supplier<T> operation) {
                VrpConsent current = consentPort.data.get(consentId);
                if (!current.isRevoked()) {
                    consentPort.save(current.revoke(CLOCK.instant(), "Concurrent revoke").mandate());
                }
                return operation.get();
            }
        };
        holder[0] = service(consentPort, new TestPaymentPort(), new TestIdempotencyPort(), new TestCachePort(), revokingLock);
        VrpConsent consent = createConsent(holder[0]);

        assertThatThrownBy(() -> holder[0].submitCollection(new SubmitVrpPaymentCommand("TPP-001", consent.consentId(),
                "IDEMP-LOCK-1", new BigDecimal("10.00"), "AED", "ix-lock")))
                .isInstanceOf(ForbiddenException.class).hasMessage("Consent Revoked");
    }

    @Test
    void theMandateIsTheConsentThePsuAuthorisedWithItsPsuAndDebtorAccount() {
        TestPsuConsentPort consents = new TestPsuConsentPort();
        consents.data.put("CONS-AUTH-PSU7", new PsuConsent("CONS-AUTH-PSU7", "TPP-001", "PSU-777",
                java.util.Set.of(PsuConsent.VRP_SCOPE), java.util.Set.of("ACC-777"),
                Instant.parse("2027-01-01T00:00:00Z"), true));
        TestDebtorAccountPort accounts = new TestDebtorAccountPort();
        accounts.accounts.put("ACC-777", new DebtorAccount("ACC-777", true, true, "AED"));
        TestConsentPort mandates = new TestConsentPort();
        RecurringPaymentService service = service(mandates, new TestPaymentPort(), new TestIdempotencyPort(),
                new TestCachePort(), new TestLockPort(), new RecordingEventPublisher(), accounts, consents);

        VrpConsent mandate = service.createConsent(new CreateVrpConsentCommand("TPP-001", "CONS-AUTH-PSU7", null,
                new BigDecimal("750.00"), "AED", null, "ix-bind", null));

        assertThat(mandate.consentId()).isEqualTo("CONS-AUTH-PSU7");
        assertThat(mandate.psuId()).isEqualTo("PSU-777");
        assertThat(mandate.debtorAccountId()).isEqualTo("ACC-777");
        assertThat(mandate.expiresAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(mandates.data).containsOnlyKeys("CONS-AUTH-PSU7");
    }

    @Test
    void noMandateWithoutAUsableConsentOfThisTppForThisPsu() {
        TestPsuConsentPort consents = new TestPsuConsentPort();
        consents.data.put("CONS-AUTH-PENDING", new PsuConsent("CONS-AUTH-PENDING", "TPP-001", "PSU-001",
                java.util.Set.of(PsuConsent.VRP_SCOPE), java.util.Set.of("ACC-DEFAULT"),
                Instant.parse("2099-01-01T00:00:00Z"), false));
        consents.data.put("CONS-AUTH-OTHER-TPP", new PsuConsent("CONS-AUTH-OTHER-TPP", "TPP-002", "PSU-001",
                java.util.Set.of(PsuConsent.VRP_SCOPE), java.util.Set.of("ACC-DEFAULT"),
                Instant.parse("2099-01-01T00:00:00Z"), true));
        TestConsentPort mandates = new TestConsentPort();
        RecordingEventPublisher events = new RecordingEventPublisher();
        RecurringPaymentService service = service(mandates, new TestPaymentPort(), new TestIdempotencyPort(),
                new TestCachePort(), new TestLockPort(), events, new TestDebtorAccountPort(), consents);

        assertThatThrownBy(() -> service.createConsent(command("MISSING-1", null, null)))
                .isInstanceOf(ForbiddenException.class).hasMessage("Consent not found or not authorised");
        assertThatThrownBy(() -> service.createConsent(command("CONS-AUTH-PENDING", null, null)))
                .isInstanceOf(ForbiddenException.class).hasMessage("Consent is not authorised by the PSU");
        assertThatThrownBy(() -> service.createConsent(command("CONS-AUTH-OTHER-TPP", null, null)))
                .isInstanceOf(ForbiddenException.class).hasMessage("Consent belongs to another TPP");
        assertThatThrownBy(() -> service.createConsent(command("CONS-AUTH-X", "PSU-SOMEONE-ELSE", "ACC-DEFAULT")))
                .isInstanceOf(ForbiddenException.class).hasMessage("PsuId does not match the consent");
        assertThatThrownBy(() -> service.createConsent(command("CONS-AUTH-Y", null, "ACC-NOT-IN-CONSENT")))
                .isInstanceOf(ForbiddenException.class).hasMessage("DebtorAccount is not covered by the consent");

        assertThat(mandates.data).isEmpty();
        assertThat(events.published).isEmpty();
    }

    @Test
    void aConsentCarriesAtMostOneMandate() {
        TestConsentPort mandates = new TestConsentPort();
        RecurringPaymentService service = service(mandates, new TestPaymentPort(), new TestIdempotencyPort(),
                new TestCachePort(), new TestLockPort());

        service.createConsent(command("CONS-AUTH-ONCE", null, "ACC-DEFAULT"));
        assertThatThrownBy(() -> service.createConsent(command("CONS-AUTH-ONCE", null, "ACC-DEFAULT")))
                .isInstanceOf(MandateAlreadyExistsException.class);
    }

    @Test
    void aCollectionIsRefusedOnceThePsuWithdrewTheConsent() {
        TestPsuConsentPort consents = new TestPsuConsentPort();
        TestPaymentPort payments = new TestPaymentPort();
        RecordingEventPublisher events = new RecordingEventPublisher();
        RecurringPaymentService service = service(new TestConsentPort(), payments, new TestIdempotencyPort(),
                new TestCachePort(), new TestLockPort(), events, new TestDebtorAccountPort(), consents);
        VrpConsent mandate = service.createConsent(command("CONS-AUTH-WITHDRAWN", null, "ACC-DEFAULT"));

        consents.data.put(mandate.consentId(), new PsuConsent(mandate.consentId(), "TPP-001", "PSU-001",
                java.util.Set.of(PsuConsent.VRP_SCOPE), java.util.Set.of("ACC-DEFAULT"),
                Instant.parse("2099-01-01T00:00:00Z"), false));

        assertThatThrownBy(() -> service.submitCollection(new SubmitVrpPaymentCommand("TPP-001", mandate.consentId(),
                "IDEMP-WD-1", new BigDecimal("10.00"), "AED", "ix-wd")))
                .isInstanceOf(ForbiddenException.class).hasMessage("Consent is not authorised by the PSU");
        assertThat(events.published).hasSize(1);
    }

    private static CreateVrpConsentCommand command(String consentId, String psuId, String account) {
        return new CreateVrpConsentCommand("TPP-001", consentId, psuId, new BigDecimal("5000.00"), "AED",
                Instant.parse("2099-01-01T00:00:00Z"), "ix-cmd", account);
    }

    @Test
    void remoteConsentAndAccountChecksRunBeforeAnyTransactionOrMandateLock() {
        AtomicInteger locksHeld = new AtomicInteger();
        List<Integer> locksHeldDuringRemoteCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        VrpLockPort countingLock = new VrpLockPort() {
            @Override
            public <T> T withConsentLock(String consentId, java.util.function.Supplier<T> operation) {
                locksHeld.incrementAndGet();
                try {
                    return operation.get();
                } finally {
                    locksHeld.decrementAndGet();
                }
            }
        };
        List<Boolean> transactionOpenDuringRemoteCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        DebtorAccountPort accounts = accountId -> {
            locksHeldDuringRemoteCalls.add(locksHeld.get());
            transactionOpenDuringRemoteCalls.add(TRANSACTIONS.active());
            return Optional.of(new DebtorAccount(accountId, true, true, "AED"));
        };
        PsuConsentPort consents = consentId -> {
            locksHeldDuringRemoteCalls.add(locksHeld.get());
            transactionOpenDuringRemoteCalls.add(TRANSACTIONS.active());
            return new TestPsuConsentPort().findConsent(consentId);
        };
        RecurringPaymentService service = service(new TestConsentPort(), new TestPaymentPort(), new TestIdempotencyPort(),
                new TestCachePort(), countingLock, new RecordingEventPublisher(), accounts, consents);

        VrpConsent mandate = service.createConsent(consentCommand("ACC-ACTIVE"));
        service.submitCollection(new SubmitVrpPaymentCommand("TPP-001", mandate.consentId(), "IDEMP-REMOTE-1",
                new BigDecimal("10.00"), "AED", "ix-remote"));

        // consent + account at creation, consent + account before the collection
        assertThat(locksHeldDuringRemoteCalls).hasSize(4).containsOnly(0);
        assertThat(transactionOpenDuringRemoteCalls).hasSize(4).containsOnly(false);
    }

    private static CreateVrpConsentCommand consentCommand(String debtorAccountId) {
        return new CreateVrpConsentCommand("TPP-001", nextConsentId(), "PSU-001", new BigDecimal("5000.00"), "AED",
                Instant.parse("2099-01-01T00:00:00Z"), "ix-create", debtorAccountId);
    }

    private static final class RecordingEventPublisher implements MandateEventPublisher {
        private final List<MandateEvent> published = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void publish(List<MandateEvent> events) {
            published.addAll(events);
        }
    }

    private static final class TestDebtorAccountPort implements DebtorAccountPort {
        private final Map<String, DebtorAccount> accounts = new ConcurrentHashMap<>();

        @Override
        public Optional<DebtorAccount> findDebtorAccount(String accountId) {
            if ("ACC-DEFAULT".equals(accountId) && !accounts.containsKey(accountId)) {
                return Optional.of(new DebtorAccount(accountId, true, true, "AED"));
            }
            return Optional.ofNullable(accounts.get(accountId));
        }
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
        return service.createConsent(consentCommand("ACC-DEFAULT"));
    }

    private static final AtomicInteger CONSENT_IDS = new AtomicInteger();

    private static String nextConsentId() {
        return "CONS-AUTH-" + CONSENT_IDS.incrementAndGet();
    }

    /**
     * Consent service stand-in: every consent id is a usable INITIATEVRP consent of
     * TPP-001 / PSU-001 over the test accounts unless a test registers another one.
     */
    private static final class TestPsuConsentPort implements PsuConsentPort {
        private final Map<String, PsuConsent> data = new ConcurrentHashMap<>();
        private final List<String> lookups = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public Optional<PsuConsent> findConsent(String consentId) {
            lookups.add(consentId);
            if (consentId.startsWith("MISSING")) {
                return Optional.empty();
            }
            return Optional.of(data.getOrDefault(consentId, new PsuConsent(consentId, "TPP-001", "PSU-001",
                    java.util.Set.of(PsuConsent.VRP_SCOPE),
                    java.util.Set.of("ACC-DEFAULT", "ACC-ACTIVE", "ACC-BLOCKED", "ACC-UNKNOWN"),
                    Instant.parse("2099-01-01T00:00:00Z"), true)));
        }
    }

    private static RecurringPaymentService service(
            VrpConsentPort consentPort,
            VrpPaymentPort paymentPort,
            VrpIdempotencyPort idempotencyPort,
            VrpCachePort cachePort,
            VrpLockPort lockPort
    ) {
        return service(consentPort, paymentPort, idempotencyPort, cachePort, lockPort,
                new RecordingEventPublisher(), new TestDebtorAccountPort());
    }

    private static RecurringPaymentService service(
            VrpConsentPort consentPort,
            VrpPaymentPort paymentPort,
            VrpIdempotencyPort idempotencyPort,
            VrpCachePort cachePort,
            VrpLockPort lockPort,
            MandateEventPublisher eventPublisher,
            DebtorAccountPort debtorAccountPort
    ) {
        return service(consentPort, paymentPort, idempotencyPort, cachePort, lockPort, eventPublisher,
                debtorAccountPort, new TestPsuConsentPort());
    }

    private static RecurringPaymentService service(
            VrpConsentPort consentPort,
            VrpPaymentPort paymentPort,
            VrpIdempotencyPort idempotencyPort,
            VrpCachePort cachePort,
            VrpLockPort lockPort,
            MandateEventPublisher eventPublisher,
            DebtorAccountPort debtorAccountPort,
            PsuConsentPort psuConsentPort
    ) {
        return new RecurringPaymentService(
                consentPort,
                paymentPort,
                idempotencyPort,
                cachePort,
                lockPort,
                eventPublisher,
                debtorAccountPort,
                psuConsentPort,
                TRANSACTIONS,
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
