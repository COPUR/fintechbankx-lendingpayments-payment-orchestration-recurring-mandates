package com.enterprise.openfinance.recurringpayments.infrastructure.persistence;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPaymentStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class InMemoryVrpPaymentAdapterTest {

    @Test
    void shouldSaveFindAndAggregateAcceptedAmount() {
        InMemoryVrpPaymentAdapter adapter = new InMemoryVrpPaymentAdapter();

        adapter.save(payment("PAY-1", "IDEMP-1", "2026-02", "100.00", VrpPaymentStatus.ACCEPTED));
        adapter.save(payment("PAY-2", "IDEMP-2", "2026-02", "200.00", VrpPaymentStatus.ACCEPTED));
        adapter.save(payment("PAY-3", "IDEMP-3", "2026-02", "300.00", VrpPaymentStatus.REJECTED));

        assertThat(adapter.findById("PAY-1")).isPresent();
        assertThat(adapter.findById("PAY-404")).isEmpty();
        assertThat(adapter.sumAcceptedAmountByConsentAndPeriod("CONS-VRP-001", "2026-02"))
                .isEqualByComparingTo("300.00");
    }

    private static VrpPayment payment(String paymentId,
                                      String idempotencyKey,
                                      String period,
                                      String amount,
                                      VrpPaymentStatus status) {
        return new VrpPayment(
                paymentId,
                "CONS-VRP-001",
                "TPP-001",
                idempotencyKey,
                new BigDecimal(amount),
                "AED",
                period,
                status,
                Instant.parse("2026-02-09T10:00:00Z")
        );
    }
}
