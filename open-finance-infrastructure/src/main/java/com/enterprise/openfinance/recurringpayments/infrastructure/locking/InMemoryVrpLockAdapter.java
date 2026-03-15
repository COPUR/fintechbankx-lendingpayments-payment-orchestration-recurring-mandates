package com.enterprise.openfinance.recurringpayments.infrastructure.locking;

import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpLockPort;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Component
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
