package com.enterprise.openfinance.recurringpayments.infrastructure.cache;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpIdempotencyRecord;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpIdempotencyPort;
import com.enterprise.openfinance.recurringpayments.infrastructure.config.RecurringPaymentsCacheProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryVrpIdempotencyAdapter implements VrpIdempotencyPort {

    private final Map<String, VrpIdempotencyRecord> records = new ConcurrentHashMap<>();
    private final int maxEntries;

    public InMemoryVrpIdempotencyAdapter(RecurringPaymentsCacheProperties properties) {
        this.maxEntries = properties.getMaxEntries();
    }

    @Override
    public Optional<VrpIdempotencyRecord> find(String idempotencyKey, String tppId, Instant now) {
        String key = key(idempotencyKey, tppId);
        VrpIdempotencyRecord record = records.get(key);
        if (record == null || !record.isActive(now)) {
            records.remove(key);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    @Override
    public void save(VrpIdempotencyRecord record) {
        if (records.size() >= maxEntries) {
            evictOne();
        }
        records.put(key(record.idempotencyKey(), record.tppId()), record);
    }

    private static String key(String idempotencyKey, String tppId) {
        return idempotencyKey + ':' + tppId;
    }

    private void evictOne() {
        String candidate = records.keySet().stream().findFirst().orElse(null);
        if (candidate != null) {
            records.remove(candidate);
        }
    }
}
