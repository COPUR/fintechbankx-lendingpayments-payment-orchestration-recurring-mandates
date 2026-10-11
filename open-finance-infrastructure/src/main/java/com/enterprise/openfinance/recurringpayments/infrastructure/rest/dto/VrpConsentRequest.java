package com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record VrpConsentRequest(
        @JsonProperty("Data") Data data
) {

    /**
     * ConsentId: the consent the PSU authorised in the consent service (required).
     * PsuId, ExpiryDateTime and DebtorAccount are optional; the consent provides
     * them, and when stated they must match it (403 otherwise).
     */
    public record Data(
            @JsonProperty("ConsentId") String consentId,
            @JsonProperty("PsuId") String psuId,
            @JsonProperty("Limit") Limit limit,
            @JsonProperty("ExpiryDateTime") Instant expiryDateTime,
            @JsonProperty("DebtorAccount") DebtorAccount debtorAccount
    ) {

        public String debtorAccountId() {
            return debtorAccount == null ? null : debtorAccount.identification();
        }
    }

    /** PSU account the collections debit; must be one of the consent's accounts. */
    public record DebtorAccount(
            @JsonProperty("Identification") String identification
    ) {
    }

    public record Limit(
            @JsonProperty("Amount") String amount,
            @JsonProperty("Currency") String currency
    ) {
    }
}
