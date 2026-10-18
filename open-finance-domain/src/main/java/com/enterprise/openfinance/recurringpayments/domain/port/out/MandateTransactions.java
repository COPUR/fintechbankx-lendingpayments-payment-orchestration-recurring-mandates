package com.enterprise.openfinance.recurringpayments.domain.port.out;

import java.util.function.Supplier;

/**
 * Runs a unit of work in one database transaction. The application calls
 * remote services (consent, accounts) before it opens one, so no database
 * connection or mandate lock is held while waiting on the network.
 */
public interface MandateTransactions {

    <T> T inTransaction(Supplier<T> work);
}
