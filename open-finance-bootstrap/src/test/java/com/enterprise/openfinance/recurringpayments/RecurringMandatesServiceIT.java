package com.enterprise.openfinance.recurringpayments;

import com.enterprise.openfinance.recurringpayments.domain.exception.MandateVersionConflictException;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpConsentPort;
import com.enterprise.openfinance.recurringpayments.infrastructure.event.OutboxRelay;
import com.enterprise.openfinance.recurringpayments.infrastructure.event.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole service against PostgreSQL: Flyway builds
 * sc_pay_recurring_mandates, Hibernate validates the entities against it, and
 * mandates go through their lifecycle over HTTP with their events landing in
 * the outbox and then on (a mocked) Kafka. Debtor accounts come from the
 * in-memory demo adapter (ACC-AED-ACTIVE, ACC-AED-BLOCKED).
 */
@SpringBootTest(properties = {
        "mandates.accounts.adapter=in-memory",
        "mandates.outbox.relay.enabled=false",
        "management.tracing.enabled=false"
})
@AutoConfigureMockMvc
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class RecurringMandatesServiceIT {

    private static final String SCHEMA = "sc_pay_recurring_mandates";
    private static final String TPP = "TPP-001";

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired VrpConsentPort consents;
    @Autowired SpringDataOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean KafkaTemplate<String, String> kafka;
    @org.springframework.boot.test.mock.mockito.SpyBean
    com.enterprise.openfinance.recurringpayments.domain.port.out.DebtorAccountPort debtorAccounts;
    @Autowired javax.sql.DataSource dataSource;
    // consent-authorization-service stand-in: consents the PSU authorised, by id.
    @MockBean com.enterprise.openfinance.recurringpayments.domain.port.out.PsuConsentPort consentService;
    private final java.util.Map<String, com.enterprise.openfinance.recurringpayments.domain.model.PsuConsent> psuConsents =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> consentCallStates = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger consentIds = new java.util.concurrent.atomic.AtomicInteger();
    // The real decoder (issuer + audience validation) is covered by SecurityConfigurationTest;
    // here a token "tok-<client>" stands for a valid Keycloak token of that TPP client.
    @MockBean JwtDecoder jwtDecoder;

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (!token.startsWith("tok-")) {
                throw new BadJwtException("invalid token");
            }
            String client = token.substring(4);
            return Jwt.withTokenValue(token).header("alg", "RS256")
                    .subject("service-account-" + client).claim("azp", client)
                    .audience(List.of("svc-pay-recurring-mandates"))
                    .claim("cnf", java.util.Map.of("jkt", ItDpop.thumbprint()))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        });
    }

    @BeforeEach
    void consentService() {
        psuConsents.clear();
        consentCallStates.clear();
        when(consentService.findConsent(anyString())).thenAnswer(invocation -> {
            consentCallStates.add("tx=" + org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            return java.util.Optional.ofNullable(psuConsents.get(invocation.<String>getArgument(0)));
        });
    }

    /** A consent TPP-001's PSU authorised for VRP over the given accounts. */
    private String psuConsent(boolean usable, String... accounts) {
        String id = "CONS-AUTH-" + consentIds.incrementAndGet();
        psuConsents.put(id, new com.enterprise.openfinance.recurringpayments.domain.model.PsuConsent(id, TPP, "PSU-001",
                java.util.Set.of("INITIATEVRP"), java.util.Set.of(accounts), Instant.parse("2099-06-01T00:00:00Z"), usable));
        return id;
    }

    @BeforeEach
    void cleanTables() {
        // As the schema owner: the runtime role may not delete mandates or payments.
        JdbcTemplate owner = PostgresTestDatabase.owner();
        owner.update("delete from " + SCHEMA + ".dpop_proof_jti");
        owner.update("delete from " + SCHEMA + ".mandate_outbox_event");
        owner.update("delete from " + SCHEMA + ".mandate_idempotency_record");
        owner.update("delete from " + SCHEMA + ".mandate_payment");
        owner.update("delete from " + SCHEMA + ".mandate_record");
    }

    @Test
    void flywayCreatesOnlyTheTablesThisServiceOwns() {
        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'sc_pay_recurring_mandates' and table_name <> 'flyway_schema_history'
                order by table_name
                """, String.class);

        assertThat(tables).containsExactly("dpop_proof_jti", "mandate_idempotency_record", "mandate_outbox_event", "mandate_payment", "mandate_record");
    }

    /**
     * The service connects as a runtime role with DML only: it cannot run DDL
     * (it owns neither the schema nor the tables), cannot delete or rewrite
     * mandates and accepted payments beyond what the code does, and cannot
     * read Flyway's history.
     */
    @Test
    void theRuntimeRoleCannotRunDdlOrDeleteMandatesAndPayments() {
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo(PostgresTestDatabase.RUNTIME_ROLE);
        JdbcTemplate runtime = PostgresTestDatabase.runtime();

        assertThatThrownBy(() -> runtime.execute("create table " + SCHEMA + ".shadow (id int)"))
                .rootCause().hasMessageContaining("permission denied for schema " + SCHEMA);
        assertThatThrownBy(() -> runtime.execute("alter table " + SCHEMA + ".mandate_record add column shadow int"))
                .rootCause().hasMessageContaining("must be owner of").hasMessageContaining("mandate_record");
        assertThatThrownBy(() -> runtime.execute("drop table " + SCHEMA + ".mandate_outbox_event"))
                .rootCause().hasMessageContaining("must be owner of").hasMessageContaining("mandate_outbox_event");
        assertThatThrownBy(() -> runtime.execute("create index ix_shadow on " + SCHEMA + ".mandate_payment (amount)"))
                .rootCause().hasMessageContaining("must be owner of").hasMessageContaining("mandate_payment");
        assertThatThrownBy(() -> runtime.execute("truncate " + SCHEMA + ".mandate_record"))
                .rootCause().hasMessageContaining("permission denied for table mandate_record");
        assertThatThrownBy(() -> runtime.update("delete from " + SCHEMA + ".mandate_record"))
                .rootCause().hasMessageContaining("permission denied for table mandate_record");
        assertThatThrownBy(() -> runtime.update("delete from " + SCHEMA + ".mandate_payment"))
                .rootCause().hasMessageContaining("permission denied for table mandate_payment");
        assertThatThrownBy(() -> runtime.update("update " + SCHEMA + ".mandate_payment set amount = 0"))
                .rootCause().hasMessageContaining("permission denied for table mandate_payment");
        assertThatThrownBy(() -> runtime.queryForObject("select count(*) from " + SCHEMA + ".flyway_schema_history", Integer.class))
                .rootCause().hasMessageContaining("permission denied for table flyway_schema_history");

        assertThat(runtime.queryForObject("select count(*) from " + SCHEMA + ".mandate_record", Integer.class)).isZero();
    }

    @Test
    void mandateLifecycleOverHttpPersistsStateAndWritesEveryEventToTheOutbox() throws Exception {
        String consentId = createConsent("5000.00", "ACC-AED-ACTIVE");

        String paymentId = submit(consentId, "IDEMP-LC-1", "1250.00");
        mvc.perform(asTpp(get("/open-finance/v1/vrp/payments/{id}", paymentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.InstructedAmount.Amount").value("1250.00"));
        mvc.perform(asTpp(delete("/open-finance/v1/vrp/payment-consents/{id}", consentId)).param("reason", "Customer request"))
                .andExpect(status().isNoContent());
        mvc.perform(asTpp(get("/open-finance/v1/vrp/payment-consents/{id}", consentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Data.Status").value("Revoked"));

        assertThat(jdbc.queryForMap("select status, version, debtor_account_id from " + SCHEMA + ".mandate_record where consent_id = ?", consentId))
                .containsEntry("status", "REVOKED")
                .containsEntry("version", 2L)
                .containsEntry("debtor_account_id", "ACC-AED-ACTIVE");
        assertThat(jdbc.queryForObject("select amount from " + SCHEMA + ".mandate_payment where payment_id = ?", BigDecimal.class, paymentId))
                .isEqualByComparingTo("1250.00");

        List<String> eventTypes = jdbc.queryForList(
                "select event_type || ':' || aggregate_version from " + SCHEMA + ".mandate_outbox_event where aggregate_id = ? order by created_seq",
                String.class, consentId);
        assertThat(eventTypes).containsExactly(
                "Payments.Mandate.Created.v1:0", "Payments.Mandate.PaymentAccepted.v1:1", "Payments.Mandate.Revoked.v1:2");

        JsonNode accepted = json.readTree(jdbc.queryForObject(
                "select payload::text from " + SCHEMA + ".mandate_outbox_event where aggregate_id = ? and event_type = 'Payments.Mandate.PaymentAccepted.v1'",
                String.class, consentId));
        assertThat(accepted.get("producer").asText()).isEqualTo("svc-pay-recurring-mandates");
        assertThat(accepted.get("correlationId").asText()).isEqualTo("it-interaction-1");
        assertThat(accepted.get("aggregateId").asText()).isEqualTo(consentId);
        assertThat(accepted.at("/data/paymentId").asText()).isEqualTo(paymentId);
        assertThat(accepted.at("/data/amount/amount").asText()).isEqualTo("1250.00");
        assertThat(accepted.at("/data/periodTotal/currency").asText()).isEqualTo("AED");
    }

    @Test
    void blockedDebtorAccountIsRefusedAndNothingIsStored() throws Exception {
        mvc.perform(asTpp(post("/open-finance/v1/vrp/payment-consents"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentJson(psuConsent(true, "ACC-AED-BLOCKED"), "5000.00", "ACC-AED-BLOCKED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("DebtorAccount cannot be used for this mandate"));

        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".mandate_record", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".mandate_outbox_event", Integer.class)).isZero();
    }

    /**
     * No account enumeration: an account the PSU did not put in the consent (not
     * theirs to use) and an account the accounts API does not know get the same
     * status and the same body, and neither creates anything.
     */
    @Test
    void anUnknownAccountAndAnAccountOutsideTheConsentGetTheSame400() throws Exception {
        MvcResult notOwned = mvc.perform(asTpp(post("/open-finance/v1/vrp/payment-consents"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentJson(psuConsent(true, "ACC-AED-ACTIVE"), "5000.00", "ACC-SOMEONE-ELSES")))
                .andExpect(status().isBadRequest())
                .andReturn();
        MvcResult unknown = mvc.perform(asTpp(post("/open-finance/v1/vrp/payment-consents"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentJson(psuConsent(true, "ACC-DOES-NOT-EXIST"), "5000.00", "ACC-DOES-NOT-EXIST")))
                .andExpect(status().isBadRequest())
                .andReturn();

        com.fasterxml.jackson.databind.node.ObjectNode unknownBody =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(unknown.getResponse().getContentAsString());
        com.fasterxml.jackson.databind.node.ObjectNode notOwnedBody =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(notOwned.getResponse().getContentAsString());
        unknownBody.remove("timestamp");
        notOwnedBody.remove("timestamp");
        assertThat(unknownBody).as("same code, message and fields; only the timestamp differs").isEqualTo(notOwnedBody);
        assertThat(json.readTree(unknown.getResponse().getContentAsString()).get("message").asText())
                .isEqualTo("DebtorAccount cannot be used for this mandate");
        assertThat(unknown.getResponse().getContentAsString()).doesNotContain("ACC-");
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".mandate_record", Integer.class)).isZero();
    }

    @Test
    void retryWithTheSameIdempotencyKeyReturnsTheFirstPaymentAndAChangedPayloadIsAConflict() throws Exception {
        String consentId = createConsent("5000.00", null);
        String first = submit(consentId, "IDEMP-RT-1", "100.00");

        MvcResult replay = mvc.perform(paymentRequest(consentId, "IDEMP-RT-1", "100.00", TPP))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-OF-Idempotency", "HIT"))
                .andReturn();
        assertThat(paymentId(replay)).isEqualTo(first);

        mvc.perform(paymentRequest(consentId, "IDEMP-RT-1", "101.00", TPP))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".mandate_payment", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".mandate_outbox_event where event_type = 'Payments.Mandate.PaymentAccepted.v1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameIdempotencyKeyCreateOnePayment() throws Exception {
        String consentId = createConsent("5000.00", null);

        List<MvcResult> results = race(4, i -> paymentRequest(consentId, "IDEMP-RACE", "100.00", TPP));

        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
        assertThat(results.stream().map(RecurringMandatesServiceIT::paymentIdUnchecked).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".mandate_payment", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentCollectionsCannotTogetherExceedTheMonthlyLimit() throws Exception {
        String consentId = createConsent("5000.00", null);

        List<MvcResult> results = race(4, i -> paymentRequest(consentId, "IDEMP-LIMIT-" + i, "2000.00", TPP));

        assertThat(results).extracting(r -> r.getResponse().getStatus()).containsOnly(201, 400)
                .filteredOn(code -> code == 201).hasSize(2);
        assertThat(jdbc.queryForObject("select sum(amount) from " + SCHEMA + ".mandate_payment where consent_id = ?", BigDecimal.class, consentId))
                .isEqualByComparingTo("4000.00");
        assertThat(jdbc.queryForObject("select version from " + SCHEMA + ".mandate_record where consent_id = ?", Long.class, consentId))
                .isEqualTo(2L);
    }

    @Test
    void anotherTppCannotReadUseOrRevokeTheMandate() throws Exception {
        String consentId = createConsent("5000.00", null);
        String paymentId = submit(consentId, "IDEMP-OWN-1", "10.00");

        mvc.perform(as("TPP-002", get("/open-finance/v1/vrp/payment-consents/{id}", consentId)))
                .andExpect(status().isForbidden());
        mvc.perform(as("TPP-002", get("/open-finance/v1/vrp/payments/{id}", paymentId)))
                .andExpect(status().isForbidden());
        mvc.perform(paymentRequest(consentId, "IDEMP-OWN-2", "10.00", "TPP-002"))
                .andExpect(status().isForbidden());
        mvc.perform(as("TPP-002", delete("/open-finance/v1/vrp/payment-consents/{id}", consentId)).param("reason", "x"))
                .andExpect(status().isForbidden());
        // A header naming another TPP than the token's client is refused.
        mvc.perform(asTpp(get("/open-finance/v1/vrp/payment-consents/{id}", consentId)).header("x-fapi-financial-id", "TPP-002"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select status from " + SCHEMA + ".mandate_record where consent_id = ?", String.class, consentId))
                .isEqualTo("AUTHORISED");
    }

    @Test
    void staleMandateVersionCannotOverwriteANewerOne() throws Exception {
        String consentId = createConsent("5000.00", null);
        VrpConsent stale = consents.findById(consentId).orElseThrow();
        submit(consentId, "IDEMP-STALE-1", "10.00");

        VrpConsent staleRevoked = stale.revoke(Clock.systemUTC().instant(), "stale").mandate();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> consents.save(staleRevoked)))
                .isInstanceOf(MandateVersionConflictException.class);
        assertThat(jdbc.queryForObject("select status from " + SCHEMA + ".mandate_record where consent_id = ?", String.class, consentId))
                .isEqualTo("AUTHORISED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aParkedEventHoldsBackItsMandatesLaterEventsWhileOtherMandatesFlow() throws Exception {
        String first = createConsent("5000.00", null);
        submit(first, "IDEMP-RELAY-1", "10.00");
        String second = createConsent("700.00", null);

        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            if (record.key().equals(first) && record.topic().endsWith(".created.v1")) {
                return CompletableFuture.failedFuture(
                        new org.apache.kafka.common.errors.RecordTooLargeException("The message is 2000000 bytes"));
            }
            return CompletableFuture.completedFuture((SendResult<String, String>) null);
        });
        OutboxRelay relay = relay();

        assertThat(relay.relayOnce()).isEqualTo(1); // payload error parks at once; only the other mandate flows
        assertThat(jdbc.queryForObject("select park_counted from " + SCHEMA + ".mandate_outbox_event where parked_at is not null",
                Boolean.class)).as("counted when the relay parked it").isTrue();
        assertThat(relay.relayOnce()).isZero();     // the parked mandate stays blocked across runs

        assertThat(outbox.countParked()).isEqualTo(1);
        assertThat(outbox.countPending()).as("the first mandate's payment event waits behind its parked event").isEqualTo(1);
        assertThat(jdbc.queryForObject("select event_type from " + SCHEMA + ".mandate_outbox_event"
                + " where published_at is null and parked_at is null", String.class))
                .isEqualTo("Payments.Mandate.PaymentAccepted.v1");
        java.util.Map<String, Object> parked = jdbc.queryForMap("select attempts, parked_reason, last_error from "
                + SCHEMA + ".mandate_outbox_event where parked_at is not null");
        assertThat(parked.get("attempts")).isEqualTo(1);
        assertThat((String) parked.get("parked_reason")).startsWith("relay: payload error RecordTooLargeException");
        assertThat((String) parked.get("last_error")).startsWith("RecordTooLargeException: The message is 2000000 bytes");
        ArgumentCaptor<ProducerRecord<String, String>> records = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafka, Mockito.times(2)).send(records.capture());
        List<String> delivered = new ArrayList<>();
        for (ProducerRecord<String, String> r : records.getAllValues()) {
            delivered.add(r.key().equals(first) ? "first:" + r.topic() : "second:" + r.topic());
        }
        assertThat(delivered).containsExactly("first:evt.pay.mandate.created.v1", "second:evt.pay.mandate.created.v1");

        // Runbook replay: un-park the event; it and then the held-back event go out in order.
        PostgresTestDatabase.owner().update("update " + SCHEMA + ".mandate_outbox_event"
                + " set parked_at = null, parked_reason = null, attempts = 0, last_error = null where parked_at is not null");
        Mockito.reset(kafka);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        assertThat(relay.relayOnce()).isEqualTo(2);
        assertThat(outbox.countPending()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBrokerOutageNeverParksOrMarksARowAndOnlyAnOperatorParksWithAReason() throws Exception {
        String mandate = createConsent("5000.00", null);
        submit(mandate, "IDEMP-OUTAGE-1", "10.00");
        String other = createConsent("700.00", null);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.TimeoutException("Expiring 1 record(s): broker unavailable")));

        for (int restart = 0; restart < 5; restart++) {
            assertThat(relay().relayOnce()).isZero(); // a fresh relay each time, as after pod restarts
        }

        assertThat(outbox.countParked()).as("no automatic parking for a non-payload error").isZero();
        assertThat(outbox.countPending()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".mandate_outbox_event"
                + " where attempts > 0 or last_error is not null", Integer.class)).as("no row is marked").isZero();
        Mockito.verify(kafka, Mockito.times(5)).send(any(ProducerRecord.class)); // each run stops at the first row

        // Only an operator parks such a row, and only with a recorded reason (V6 check constraint).
        JdbcTemplate owner = PostgresTestDatabase.owner();
        String oldest = "(select event_id from " + SCHEMA + ".mandate_outbox_event order by created_seq limit 1)";
        assertThatThrownBy(() -> owner.update("update " + SCHEMA + ".mandate_outbox_event set parked_at = now()"
                + " where event_id = " + oldest))
                .rootCause().hasMessageContaining("ck_outbox_parked_reason");
        owner.update("update " + SCHEMA + ".mandate_outbox_event set parked_at = now(),"
                + " parked_reason = 'operator: INC-1 topic ACL missing' where event_id = " + oldest);

        Mockito.reset(kafka);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        OutboxRelay countingRelay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
                Clock.systemUTC(), 100, Duration.ofSeconds(5), Duration.ofDays(7), meters);
        assertThat(countingRelay.relayOnce()).isEqualTo(1); // only the other mandate; the parked mandate stays held back
        assertThat(countingRelay.relayOnce()).isZero();
        assertThat(meters.get("outbox.parked.events").tag("exception", "OperatorPark").counter().count())
                .as("the operator park is counted once, not on every run").isEqualTo(1);
        ArgumentCaptor<ProducerRecord<String, String>> records = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafka).send(records.capture());
        assertThat(records.getValue().key()).isEqualTo(other);
        assertThat(outbox.countPending()).isEqualTo(1);
    }

    private OutboxRelay relay() {
        return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
                Clock.systemUTC(), 100, Duration.ofSeconds(5), Duration.ofDays(7),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void apiRejectsCallsWithoutAValidToken() throws Exception {
        mvc.perform(get("/open-finance/v1/vrp/payment-consents/{id}", "CONS-ANY")
                        .header("x-fapi-interaction-id", "it-1").header("DPoP", "proof"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/open-finance/v1/vrp/payment-consents/{id}", "CONS-ANY")
                        .header("Authorization", "Bearer forged").header("x-fapi-interaction-id", "it-1").header("DPoP", "proof"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void vrpApiRequiresTheDpopSchemeAProofAndAFreshJti() throws Exception {
        String url = "http://localhost/open-finance/v1/vrp/payment-consents/CONS-ANY";
        // Plain Bearer with a valid proof: 401.
        mvc.perform(get(url).with(ItDpop.dpop("Bearer", "tok-" + TPP)).header("x-fapi-interaction-id", "it-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("DPoP error=\"invalid_dpop_proof\"")));
        // DPoP scheme without a proof: 401.
        mvc.perform(get(url).header("Authorization", "DPoP tok-" + TPP).header("x-fapi-interaction-id", "it-1"))
                .andExpect(status().isUnauthorized());
        // A proof is accepted once; the replay is refused by the shared jti table.
        String proof = ItDpop.proof("GET", url, "tok-" + TPP);
        mvc.perform(get(url).header("Authorization", "DPoP tok-" + TPP).header("DPoP", proof)
                        .header("x-fapi-interaction-id", "it-1"))
                .andExpect(status().isNotFound());
        mvc.perform(get(url).header("Authorization", "DPoP tok-" + TPP).header("DPoP", proof)
                        .header("x-fapi-interaction-id", "it-1"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".dpop_proof_jti", Integer.class)).isEqualTo(1);
        // A proof for another method is refused.
        mvc.perform(get(url).header("Authorization", "DPoP tok-" + TPP)
                        .header("DPoP", ItDpop.proof("DELETE", url, "tok-" + TPP)).header("x-fapi-interaction-id", "it-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedAndUnsupportedRequestsKeepTheirOwnStatusInsteadOf401Or403() throws Exception {
        mvc.perform(asTpp(post("/open-finance/v1/vrp/payment-consents"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"Data\": {"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(asTpp(post("/open-finance/v1/vrp/payment-consents"))
                        .contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(asTpp(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/open-finance/v1/vrp/payments/{id}", "P-1")))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void htuIsTheUrlTheGatewayForwardsAndAProofForAnotherUrlIs401() throws Exception {
        // The ingress gateway sets X-Forwarded-Proto/Host/Port (server.forward-headers-strategy=framework).
        String publicUrl = "https://api.fintechbankx.example/open-finance/v1/vrp/payment-consents/CONS-ANY";
        mvc.perform(get("/open-finance/v1/vrp/payment-consents/CONS-ANY")
                        .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "api.fintechbankx.example")
                        .header("X-Forwarded-Port", "443").header("X-Forwarded-For", "203.0.113.9")
                        .header("Authorization", "DPoP tok-" + TPP)
                        .header("DPoP", ItDpop.proof("GET", publicUrl, "tok-" + TPP))
                        .header("x-fapi-interaction-id", "it-1"))
                .andExpect(status().isNotFound());
        // The pod-internal URL is not what the TPP signed: 401.
        mvc.perform(get("/open-finance/v1/vrp/payment-consents/CONS-ANY")
                        .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "api.fintechbankx.example")
                        .header("X-Forwarded-Port", "443")
                        .header("Authorization", "DPoP tok-" + TPP)
                        .header("DPoP", ItDpop.proof("GET", "http://localhost/open-finance/v1/vrp/payment-consents/CONS-ANY", "tok-" + TPP))
                        .header("x-fapi-interaction-id", "it-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void remoteCallsHoldNoTransactionAndNoDatabaseConnection() throws Exception {
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.zaxxer.hikari.HikariPoolMXBean pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class)
                .getHikariPoolMXBean();
        Mockito.doAnswer(invocation -> {
            seen.add("tx=" + org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive() + ",connections=" + pool.getActiveConnections());
            return invocation.callRealMethod();
        }).when(debtorAccounts).findDebtorAccount(anyString());

        String consentId = createConsent("5000.00", "ACC-AED-ACTIVE");
        submit(consentId, "IDEMP-REMOTE-1", "10.00");

        assertThat(seen).hasSize(2).containsOnly("tx=false,connections=0");
        assertThat(consentCallStates).hasSize(2).containsOnly("tx=false");
    }

    @Test
    void aMandateNeedsAConsentThePsuAuthorisedForThisTppAndOnlyOneMandatePerConsent() throws Exception {
        // Unknown consent, consent still pending, consent of another PSU: 403, nothing stored.
        mvc.perform(asTpp(post("/open-finance/v1/vrp/payment-consents")).contentType(MediaType.APPLICATION_JSON)
                        .content(consentJson("CONS-UNKNOWN", "5000.00", null)))
                .andExpect(status().isForbidden());
        mvc.perform(asTpp(post("/open-finance/v1/vrp/payment-consents")).contentType(MediaType.APPLICATION_JSON)
                        .content(consentJson(psuConsent(false, "ACC-AED-ACTIVE"), "5000.00", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Consent not found or not authorised"));
        mvc.perform(asTpp(post("/open-finance/v1/vrp/payment-consents")).contentType(MediaType.APPLICATION_JSON)
                        .content(consentJson(psuConsent(true, "ACC-AED-ACTIVE"), "5000.00", null)
                                .replace("PSU-001", "PSU-SOMEONE-ELSE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("PsuId does not match the consent"));
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".mandate_record", Integer.class)).isZero();

        // Two concurrent requests for the same consent: one mandate, one 409.
        String consentId = psuConsent(true, "ACC-AED-ACTIVE");
        List<MvcResult> results = race(2, i -> asTpp(post("/open-finance/v1/vrp/payment-consents"))
                .contentType(MediaType.APPLICATION_JSON).content(consentJson(consentId, "5000.00", null)));
        assertThat(results).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select consent_id from " + SCHEMA + ".mandate_record", String.class))
                .isEqualTo(consentId);
    }

    @Test
    void everyConsentTheCallerMayNotUseGetsOneForbiddenBodyAndTheReasonOnlyInTheLog(
            org.springframework.boot.test.system.CapturedOutput log) throws Exception {
        Instant future = Instant.parse("2099-06-01T00:00:00Z");
        java.util.Map<String, String> refusals = new java.util.LinkedHashMap<>();
        refusals.put("CONS-UNKNOWN", "NOT_FOUND");
        refusals.put(putPsuConsent(TPP, java.util.Set.of("INITIATEVRP"), future, false), "NOT_AUTHORISED");
        refusals.put(putPsuConsent(TPP, java.util.Set.of("INITIATEVRP"), Instant.parse("2020-01-01T00:00:00Z"), true),
                "EXPIRED");
        refusals.put(putPsuConsent("TPP-OTHER", java.util.Set.of("INITIATEVRP"), future, true), "OTHER_TPP");
        refusals.put(putPsuConsent(TPP, java.util.Set.of("READACCOUNTS"), future, true), "MISSING_SCOPE");

        List<String> bodies = new ArrayList<>();
        for (var refusal : refusals.entrySet()) {
            String interactionId = "it-refusal-" + refusal.getValue();
            MvcResult result = mvc.perform(post("/open-finance/v1/vrp/payment-consents")
                            .with(ItDpop.dpop("DPoP", "tok-" + TPP))
                            .header("x-fapi-interaction-id", interactionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(consentJson(refusal.getKey(), "5000.00", null)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("Consent not found or not authorised"))
                    .andReturn();
            com.fasterxml.jackson.databind.node.ObjectNode body =
                    (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(result.getResponse().getContentAsString());
            assertThat(body.remove("interactionId").asText()).isEqualTo(interactionId);
            body.remove("timestamp");
            bodies.add(body.toString());
            assertThat(log.getOut()).contains("reason=" + refusal.getValue() + " interactionId=" + interactionId);
        }
        assertThat(bodies).hasSize(5).containsOnly(bodies.get(0));
        assertThat(String.join("", bodies)).doesNotContain("PSU", "TPP", "INITIATEVRP", "expired", "another");

        // The per-collection re-check gives the same answer once the PSU withdraws the consent.
        String consentId = createConsent("5000.00", null);
        psuConsents.computeIfPresent(consentId, (id, c) -> new com.enterprise.openfinance.recurringpayments.domain.model
                .PsuConsent(id, c.participantId(), c.customerId(), c.scopes(), c.accountIds(), c.expiresAt(), false));
        mvc.perform(paymentRequest(consentId, "IDEMP-REFUSED-1", "10.00", TPP))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Consent not found or not authorised"));
        assertThat(log.getOut()).contains("reason=NOT_AUTHORISED interactionId=it-interaction-1");
    }

    private String putPsuConsent(String participant, java.util.Set<String> scopes, Instant expiry, boolean usable) {
        String id = "CONS-AUTH-" + consentIds.incrementAndGet();
        psuConsents.put(id, new com.enterprise.openfinance.recurringpayments.domain.model.PsuConsent(id, participant,
                "PSU-001", scopes, java.util.Set.of("ACC-AED-ACTIVE"), expiry, usable));
        return id;
    }

    @Test
    void consentServiceOutageFailsClosedWith503() throws Exception {
        when(consentService.findConsent(anyString())).thenThrow(
                new com.enterprise.openfinance.recurringpayments.infrastructure.external.ConsentServiceUnavailableException(
                        "down", null));
        mvc.perform(asTpp(post("/open-finance/v1/vrp/payment-consents")).contentType(MediaType.APPLICATION_JSON)
                        .content(consentJson("CONS-ANY", "5000.00", null)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void healthAndUnknownPathsAreHandledBySecurity() throws Exception {
        mvc.perform(asTpp(get("/internal/anything"))).andExpect(status().isForbidden());
    }

    private interface RequestFactory {
        MockHttpServletRequestBuilder build(int index) throws Exception;
    }

    private List<MvcResult> race(int parallel, RequestFactory requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(parallel);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < parallel; i++) {
                MockHttpServletRequestBuilder request = requests.build(i);
                Callable<MvcResult> call = () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return mvc.perform(request).andReturn();
                };
                futures.add(executor.submit(call));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private String createConsent(String limit, String debtorAccount) throws Exception {
        String consentId = psuConsent(true, debtorAccount == null ? "ACC-AED-ACTIVE" : debtorAccount);
        MvcResult result = mvc.perform(asTpp(post("/open-finance/v1/vrp/payment-consents"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentJson(consentId, limit, debtorAccount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.Data.Status").value("Authorised"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).at("/Data/ConsentId").asText();
    }

    private String submit(String consentId, String idempotencyKey, String amount) throws Exception {
        return paymentId(mvc.perform(paymentRequest(consentId, idempotencyKey, amount, TPP))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private MockHttpServletRequestBuilder paymentRequest(String consentId, String idempotencyKey, String amount, String tpp) {
        return as(tpp, post("/open-finance/v1/vrp/payments"))
                .header("x-idempotency-key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"Data": {"ConsentId": "%s", "InstructedAmount": {"Amount": "%s", "Currency": "AED"}}}
                        """.formatted(consentId, amount));
    }

    private String paymentId(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).at("/Data/PaymentId").asText();
    }

    private static String paymentIdUnchecked(MvcResult result) {
        try {
            return new ObjectMapper().readTree(result.getResponse().getContentAsString()).at("/Data/PaymentId").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String consentJson(String consentId, String limit, String debtorAccount) {
        String account = debtorAccount == null ? "" : ", \"DebtorAccount\": {\"Identification\": \"" + debtorAccount + "\"}";
        return """
                {"Data": {"ConsentId": "%s", "PsuId": "PSU-001", "Limit": {"Amount": "%s", "Currency": "AED"},
                 "ExpiryDateTime": "2099-01-01T00:00:00Z"%s}}
                """.formatted(consentId, limit, account);
    }

    private static MockHttpServletRequestBuilder asTpp(MockHttpServletRequestBuilder request) {
        return as(TPP, request);
    }

    private static MockHttpServletRequestBuilder as(String tpp, MockHttpServletRequestBuilder request) {
        return request
                .with(ItDpop.dpop("DPoP", "tok-" + tpp))
                .header("x-fapi-interaction-id", "it-interaction-1");
    }
}
