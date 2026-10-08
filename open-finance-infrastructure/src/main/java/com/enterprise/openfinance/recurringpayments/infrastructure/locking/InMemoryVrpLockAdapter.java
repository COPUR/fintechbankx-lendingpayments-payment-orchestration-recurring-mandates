package com.enterprise.openfinance.recurringpayments.infrastructure.locking;

import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpLockPort;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Single-JVM adapter kept for unit tests and as a reference; not a Spring bean.
 * The service uses the PostgreSQL adapters so state survives restarts and is
 * shared across replicas.
 */
public class InMemoryVrpLockAdapter implements VrpLockPort {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T withConsentLock(String consentId, Supplier<T> operation) {
        ReentrantLock lock = locks.computeIfAbsent(consentId, key -> new ReentrantLock());
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }
}
