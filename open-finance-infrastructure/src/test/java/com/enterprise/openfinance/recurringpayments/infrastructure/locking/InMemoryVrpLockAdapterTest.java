package com.enterprise.openfinance.recurringpayments.infrastructure.locking;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class InMemoryVrpLockAdapterTest {

    @Test
    void shouldExecuteCriticalSection() {
        InMemoryVrpLockAdapter adapter = new InMemoryVrpLockAdapter();
        AtomicInteger counter = new AtomicInteger();

        Integer result = adapter.withConsentLock("CONS-VRP-001", () -> counter.incrementAndGet());

        assertThat(result).isEqualTo(1);
        assertThat(counter.get()).isEqualTo(1);
    }
}
