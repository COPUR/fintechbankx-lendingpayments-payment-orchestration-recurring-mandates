package com.enterprise.openfinance.recurringpayments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * svc-pay-recurring-mandates: variable recurring payment mandates (VRP
 * consents) and the collections made under them, extracted from the
 * open-finance-context of enterprise-loan-management-system.
 */
@SpringBootApplication
public class RecurringMandatesApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecurringMandatesApplication.class, args);
    }
}
