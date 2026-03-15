package com.enterprise.openfinance.recurringpayments.infrastructure.cache;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpCachePort;
import com.enterprise.openfinance.recurringpayments.infrastructure.config.RecurringPaymentsCacheProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryVrpCacheAdapter implements VrpCachePort {

    private final Map<String, CacheItem<VrpConsent>> consentCache = new ConcurrentHashMap<>();
    private final Map<String, CacheItem<VrpPayment>> paymentCache = new ConcurrentHashMap<>();
    private final int maxEntries;

    public InMemoryVrpCacheAdapter(RecurringPaymentsCacheProperties properties) {
        this.maxEntries = properties.getMaxEntries();
    }

    @Override
    public Optional<VrpConsent> getConsent(String key, Instant now) {
        return get(consentCache, key, now);
    }

    @Override
    public void putConsent(String key, VrpConsent consent, Instant expiresAt) {
        put(consentCache, key, consent, expiresAt);
    }

    @Override
    public Optional<VrpPayment> getPayment(String key, Instant now) {
        return get(paymentCache, key, now);
    }

    @Override
    public void putPayment(String key, VrpPayment payment, Instant expiresAt) {
        put(paymentCache, key, payment, expiresAt);
    }

    private static <T> Optional<T> get(Map<String, CacheItem<T>> cache, String key, Instant now) {
        CacheItem<T> item = cache.get(key);
        if (item == null) {
            return Optional.empty();
        }
        if (!item.expiresAt().isAfter(now)) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(item.value());
    }

    private <T> void put(Map<String, CacheItem<T>> cache, String key, T value, Instant expiresAt) {
        if (cache.size() >= maxEntries) {
            evictOne(cache);
        }
        cache.put(key, new CacheItem<>(value, expiresAt));
    }

    private static <T> void evictOne(Map<String, CacheItem<T>> cache) {
        String candidate = cache.keySet().stream().findFirst().orElse(null);
        if (candidate != null) {
            cache.remove(candidate);
        }
    }

    private record CacheItem<T>(T value, Instant expiresAt) {
    }
}
