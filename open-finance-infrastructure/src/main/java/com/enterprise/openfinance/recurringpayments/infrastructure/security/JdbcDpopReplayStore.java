package com.enterprise.openfinance.recurringpayments.infrastructure.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/**
 * DPoP jti replay cache in this service's schema (dpop_proof_jti), shared by
 * all pods. A (jkt, jti) pair is claimed with one upsert that only succeeds
 * for a new pair or one whose earlier use has expired, so two pods cannot
 * both accept the same proof.
 */
public class JdbcDpopReplayStore implements DpopReplayStore {

    private static final String CLAIM = """
            INSERT INTO dpop_proof_jti (jkt, jti, expires_at) VALUES (?, ?, ?)
            ON CONFLICT (jkt, jti) DO UPDATE SET expires_at = EXCLUDED.expires_at
            WHERE dpop_proof_jti.expires_at < ?
            """;
    private static final String PURGE = "DELETE FROM dpop_proof_jti WHERE expires_at < ?";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcDpopReplayStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean claim(String jkt, String jti, Instant expiresAt) {
        return jdbc.update(CLAIM, jkt, jti, Timestamp.from(expiresAt), Timestamp.from(clock.instant())) == 1;
    }

    @Scheduled(fixedDelayString = "${security.dpop.purge-interval:PT10M}")
    public int purgeExpired() {
        return jdbc.update(PURGE, Timestamp.from(clock.instant()));
    }
}
