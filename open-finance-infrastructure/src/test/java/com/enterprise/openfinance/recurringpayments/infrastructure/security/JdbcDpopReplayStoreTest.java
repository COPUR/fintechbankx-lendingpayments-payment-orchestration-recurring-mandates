package com.enterprise.openfinance.recurringpayments.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The SQL itself runs against PostgreSQL in RecurringMandatesServiceIT. */
class JdbcDpopReplayStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-08T07:00:00Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcDpopReplayStore store = new JdbcDpopReplayStore(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aJtiIsClaimedWhenTheUpsertTouchesARowAndIsAReplayOtherwise() {
        Instant expires = NOW.plusSeconds(330);
        when(jdbc.update(contains("ON CONFLICT"), eq("jkt-1"), eq("jti-1"), eq(Timestamp.from(expires)), eq(Timestamp.from(NOW))))
                .thenReturn(1, 0);

        assertThat(store.claim("jkt-1", "jti-1", expires)).isTrue();
        assertThat(store.claim("jkt-1", "jti-1", expires)).isFalse();
    }

    @Test
    void expiredProofIdsArePurged() {
        when(jdbc.update(anyString(), any(Timestamp.class))).thenReturn(3);

        assertThat(store.purgeExpired()).isEqualTo(3);
        verify(jdbc).update(contains("DELETE FROM dpop_proof_jti"), eq(Timestamp.from(NOW)));
    }
}
