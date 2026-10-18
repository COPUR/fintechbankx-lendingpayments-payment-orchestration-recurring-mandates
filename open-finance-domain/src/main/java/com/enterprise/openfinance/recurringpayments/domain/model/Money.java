package com.enterprise.openfinance.recurringpayments.domain.model;

import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;

/**
 * An amount in one ISO 4217 currency, held at exactly the currency's minor
 * units (2 for AED). Amounts with more decimals are refused rather than
 * rounded, so nothing changes silently between request, store and event.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency is required");
        }
        if (amount.signum() < 0) {
            throw new BusinessRuleViolationException("Amount must not be negative");
        }
        int minorUnits = Math.max(currency.getDefaultFractionDigits(), 0);
        if (amount.stripTrailingZeros().scale() > minorUnits) {
            throw new BusinessRuleViolationException(
                    "Amount has more than " + minorUnits + " decimal places for " + currency.getCurrencyCode());
        }
        amount = amount.setScale(minorUnits);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, currencyOf(currencyCode));
    }

    public static Money zero(String currencyCode) {
        return of(BigDecimal.ZERO, currencyCode);
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    public boolean hasCurrency(String candidateCode) {
        return candidateCode != null && currencyCode().equalsIgnoreCase(candidateCode.trim());
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public Money plus(Money other) {
        ensureSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public boolean exceeds(Money other) {
        ensureSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    @Override
    public String toString() {
        return amount.toPlainString() + ' ' + currencyCode();
    }

    private void ensureSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new BusinessRuleViolationException("Currency mismatch");
        }
    }

    private static Currency currencyOf(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
        try {
            return Currency.getInstance(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessRuleViolationException("Unknown currency " + code.trim());
        }
    }
}
