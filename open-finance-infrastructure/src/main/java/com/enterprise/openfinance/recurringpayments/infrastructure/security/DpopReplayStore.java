package com.enterprise.openfinance.recurringpayments.infrastructure.security;

import java.time.Instant;

/** Remembers DPoP proof ids (jti) per key until they can no longer be accepted. */
@FunctionalInterface
public interface DpopReplayStore {

    /**
     * @return true if (jkt, jti) was not seen before (or its earlier use has expired) and is now claimed;
     *         false if it is a replay
     */
    boolean claim(String jkt, String jti, Instant expiresAt);
}
