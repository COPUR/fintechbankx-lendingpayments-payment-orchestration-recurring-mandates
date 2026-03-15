package com.enterprise.openfinance.recurringpayments.domain.port.out;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;

import java.math.BigDecimal;
import java.util.Optional;

public interface VrpPaymentPort {
    VrpPayment save(VrpPayment payment);

    Optional<VrpPayment> findById(String paymentId);

    BigDecimal sumAcceptedAmountByConsentAndPeriod(String consentId, String periodKey);
}
