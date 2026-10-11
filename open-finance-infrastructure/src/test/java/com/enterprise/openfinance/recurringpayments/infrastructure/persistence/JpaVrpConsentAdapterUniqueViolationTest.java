package com.enterprise.openfinance.recurringpayments.infrastructure.persistence;

import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** The race itself runs against PostgreSQL in RecurringMandatesServiceIT. */
class JpaVrpConsentAdapterUniqueViolationTest {

    @Test
    void onlyAPostgresUniqueViolationMeansTheMandateAlreadyExists() {
        assertThat(JpaVrpConsentAdapter.isUniqueViolation(
                new PersistenceException(new RuntimeException(new SQLException("dup", "23505"))))).isTrue();
        assertThat(JpaVrpConsentAdapter.isUniqueViolation(
                new PersistenceException(new SQLException("fk", "23503")))).isFalse();
        assertThat(JpaVrpConsentAdapter.isUniqueViolation(new PersistenceException("no cause"))).isFalse();
    }
}
