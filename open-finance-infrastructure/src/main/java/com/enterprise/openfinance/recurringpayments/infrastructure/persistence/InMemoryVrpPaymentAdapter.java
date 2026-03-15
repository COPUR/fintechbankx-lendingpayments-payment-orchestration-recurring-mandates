package com.enterprise.openfinance.recurringpayments.infrastructure.persistence;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpPaymentPort;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryVrpPaymentAdapter implements VrpPaymentPort {

    private final Map<String, VrpPayment> data = new ConcurrentHashMap<>();

    @Override
    public VrpPayment save(VrpPayment payment) {
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
