package com.enterprise.openfinance.recurringpayments;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

/**
 * svc-pay-recurring-mandates: variable recurring payment mandates (VRP
 * consents) and the collections made under them, extracted from the
 * open-finance-context of enterprise-loan-management-system.
 *
 * With the first argument "migrate" the image runs only the Flyway migrations
 * (as the schema owner, DB_MIGRATION_USERNAME / _PASSWORD) and exits: the Helm
 * pre-install/pre-upgrade Job. The service pods never hold the owner
 * credential and run with spring.flyway.enabled=false.
 */
@SpringBootApplication
public class RecurringMandatesApplication {

    static final String MIGRATE = "migrate";

    public static void main(String[] args) {
        if (args.length > 0 && MIGRATE.equals(args[0])) {
            System.exit(run(args));
        }
        SpringApplication.run(RecurringMandatesApplication.class, args);
    }

    /**
     * Runs the migrations and returns the process exit code (0 on success).
     * Only the DataSource and Flyway are configured: no web server, Kafka,
     * security, HTTP clients or application beans.
     */
    static int run(String... args) {
        int skip = args.length > 0 && MIGRATE.equals(args[0]) ? 1 : 0;
        String[] rest = Arrays.copyOfRange(args, skip, args.length);
        SpringApplication migration = new SpringApplication(DatabaseMigration.class);
        migration.setWebApplicationType(WebApplicationType.NONE);
        migration.setBannerMode(Banner.Mode.OFF);
        String[] withFlyway = Arrays.copyOf(rest, rest.length + 1);
        withFlyway[rest.length] = "--spring.flyway.enabled=true";
        try (ConfigurableApplicationContext context = migration.run(withFlyway)) {
            return SpringApplication.exit(context);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** Not a component (no stereotype), so the service's component scan never picks it up. */
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    static class DatabaseMigration {
    }
}
