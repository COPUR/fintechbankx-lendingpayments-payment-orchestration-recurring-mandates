package com.enterprise.openfinance.recurringpayments.domain.model;

import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Money value object")
class MoneyTest {

    @Test
    @DisplayName("normalises the currency code and the scale to the currency's minor units")
    void normalisesCurrencyAndScale() {
        Money money = Money.of(new BigDecimal("10.5"), " aed ");

        assertThat(money.currencyCode()).isEqualTo("AED");
        assertThat(money.amount()).isEqualTo(new BigDecimal("10.50"));
        assertThat(Money.of(new BigDecimal("10.0000"), "AED")).isEqualTo(Money.of(new BigDecimal("10"), "AED"));
    }

    @Test
    @DisplayName("refuses more decimals than the currency allows, unknown currencies and negative amounts")
    void refusesInvalidAmounts() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("0.001"), "AED"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Amount has more than 2 decimal places for AED");
        assertThatThrownBy(() -> Money.of(new BigDecimal("1.5"), "JPY"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Amount has more than 0 decimal places for JPY");
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "XYZ1"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Unknown currency XYZ1");
        assertThatThrownBy(() -> Money.of(new BigDecimal("-0.01"), "AED"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Amount must not be negative");
        assertThatThrownBy(() -> Money.of(null, "AED")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("adds and compares only within one currency")
    void addsAndComparesWithinOneCurrency() {
        Money limit = Money.of(new BigDecimal("5000.00"), "AED");
        Money spent = Money.of(new BigDecimal("4000.00"), "AED");

        assertThat(spent.plus(Money.of(new BigDecimal("1000.00"), "AED")).exceeds(limit)).isFalse();
        assertThat(spent.plus(Money.of(new BigDecimal("1000.01"), "AED")).exceeds(limit)).isTrue();
        assertThat(Money.zero("AED").isPositive()).isFalse();
        assertThat(limit.isPositive()).isTrue();
        assertThatThrownBy(() -> limit.plus(Money.of(BigDecimal.ONE, "USD")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Currency mismatch");
        assertThatThrownBy(() -> limit.exceeds(Money.of(BigDecimal.ONE, "USD")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Currency mismatch");
        assertThat(limit.hasCurrency("aed")).isTrue();
        assertThat(limit.hasCurrency(null)).isFalse();
        assertThat(limit).hasToString("5000.00 AED");
    }
}
