package com.enterprise.openfinance.recurringpayments;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployed migration step: the Helm pre-install/pre-upgrade Job runs the
 * image with the argument "migrate". It runs Flyway as the schema owner,
 * grants the runtime role, starts no web server, Kafka, security or HTTP
 * clients, and exits 0. Runs against a scratch schema so the shared one is
 * untouched.
 */
class DatabaseMigrationIT {

    private static final String SCHEMA = "sc_pay_mandates_migration_job_it";

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @AfterEach
    void dropScratchSchema() {
        PostgresTestDatabase.owner().execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void migrateRunsFlywayAsTheOwnerGrantsTheRuntimeRoleAndExits() {
        PostgresTestDatabase.owner().execute("drop schema if exists " + SCHEMA + " cascade");
        PostgresTestDatabase.runtime().queryForObject("select 1", Integer.class); // the runtime role exists

        int exitCode = RecurringMandatesApplication.run(
                "migrate",
                "--spring.datasource.url=" + PostgresTestDatabase.url(),
                "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
                "--DB_MIGRATION_USERNAME=" + PostgresTestDatabase.ownerUser(),
                "--DB_MIGRATION_PASSWORD=" + PostgresTestDatabase.ownerPassword(),
                "--spring.flyway.schemas=" + SCHEMA,
                "--spring.flyway.default-schema=" + SCHEMA);

        assertThat(exitCode).isZero();
        JdbcTemplate owner = PostgresTestDatabase.owner();
        assertThat(owner.queryForObject(
                "select count(*) from " + SCHEMA + ".flyway_schema_history where success", Integer.class))
                .isGreaterThanOrEqualTo(5);
        assertThat(owner.queryForObject(
                "select tableowner from pg_tables where schemaname = ? and tablename = 'mandate_record'", String.class, SCHEMA))
                .isEqualTo(PostgresTestDatabase.ownerUser());
        assertThat(owner.queryForObject("select has_table_privilege(?, ?, 'INSERT')", Boolean.class,
                PostgresTestDatabase.RUNTIME_ROLE, SCHEMA + ".mandate_record")).isTrue();
        assertThat(owner.queryForObject("select has_table_privilege(?, ?, 'DELETE')", Boolean.class,
                PostgresTestDatabase.RUNTIME_ROLE, SCHEMA + ".mandate_record")).isFalse();
        assertThat(owner.queryForObject("select has_schema_privilege(?, ?, 'CREATE')", Boolean.class,
                PostgresTestDatabase.RUNTIME_ROLE, SCHEMA)).isFalse();
    }

    @Test
    void migrateFailsWithANonZeroExitCodeWhenTheDatabaseRefuses() {
        int exitCode = RecurringMandatesApplication.run(
                "migrate",
                "--spring.datasource.url=" + PostgresTestDatabase.url(),
                "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
                "--DB_MIGRATION_USERNAME=" + PostgresTestDatabase.ownerUser(),
                "--DB_MIGRATION_PASSWORD=wrong-password",
                "--spring.flyway.schemas=" + SCHEMA,
                "--spring.flyway.default-schema=" + SCHEMA);

        assertThat(exitCode).isNotZero();
    }
}
