package com.enterprise.openfinance.recurringpayments.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The four ArchUnit rules of the FinTechBankX service guardrails (ADR-028),
 * checked over the production classes of every module of this service.
 */
@DisplayName("Hexagonal architecture (FinTechBankX service guardrails, section 3)")
class HexagonalArchitectureTest {

    private static final String ROOT = "com.enterprise.openfinance.recurringpayments";

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    private static final DescribedPredicate<JavaClass> INBOUND_ADAPTER = new DescribedPredicate<>(
            "controllers or message listeners") {
        @Override
        public boolean test(JavaClass type) {
            return type.isAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    || type.isAnnotatedWith("org.springframework.stereotype.Controller")
                    || type.getMethods().stream().anyMatch(method ->
                    method.isAnnotatedWith("org.springframework.kafka.annotation.KafkaListener"));
        }
    };

    @Test
    @DisplayName("1. the domain depends on no application, infrastructure, Spring, JPA, Kafka or Mongo type")
    void domainIsFrameworkFree() {
        noClasses().that().resideInAPackage(ROOT + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".application..", ROOT + ".infrastructure..",
                        "org.springframework..", "jakarta.persistence..", "org.hibernate..",
                        "org.apache.kafka..", "com.mongodb..", "org.bson..", "com.fasterxml.jackson..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("2. the application depends on no infrastructure type")
    void applicationDoesNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage(ROOT + ".application..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("3. controllers and listeners drive domain.port.in, never application classes")
    void inboundAdaptersDependOnUseCasePorts() {
        classes().that(INBOUND_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".domain.port.in..")
                .check(PRODUCTION);
        noClasses().that(INBOUND_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".application..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("4. implementations of domain.port.out live in infrastructure")
    void outboundPortImplementationsLiveInInfrastructure() {
        classes().that().implement(resideInAPackage(ROOT + ".domain.port.out.."))
                .and().areNotInterfaces()
                .should().resideInAPackage(ROOT + ".infrastructure..")
                .check(PRODUCTION);
    }
}
