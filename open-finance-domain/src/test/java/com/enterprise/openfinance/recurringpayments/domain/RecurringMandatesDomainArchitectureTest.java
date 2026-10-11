package com.enterprise.openfinance.recurringpayments.domain;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The mandate domain depends on the JDK only (fbx-hexagonal-service, dependency rule). */
class RecurringMandatesDomainArchitectureTest {

    private static final JavaClasses DOMAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.enterprise.openfinance.recurringpayments.domain");

    @Test
    void domainDoesNotDependOnApplicationOrInfrastructure() {
        noClasses()
                .that().resideInAPackage("com.enterprise.openfinance.recurringpayments.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.enterprise.openfinance.recurringpayments.application..",
                        "com.enterprise.openfinance.recurringpayments.infrastructure..")
                .check(DOMAIN);
    }

    @Test
    void domainIsFreeOfFrameworkPersistenceAndMessagingTypes() {
        noClasses()
                .that().resideInAPackage("com.enterprise.openfinance.recurringpayments.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "org.hibernate..",
                        "com.fasterxml.jackson..", "org.apache.kafka..", "org.springframework.data.mongodb..",
                        "lombok..")
                .check(DOMAIN);
    }
}
