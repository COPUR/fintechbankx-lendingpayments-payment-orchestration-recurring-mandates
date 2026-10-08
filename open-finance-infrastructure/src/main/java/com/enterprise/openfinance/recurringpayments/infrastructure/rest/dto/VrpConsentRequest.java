package com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record VrpConsentRequest(
        @JsonProperty("Data") Data data
) {

    public record Data(
            @JsonProperty("PsuId") String psuId,
            @JsonProperty("Limit") Limit limit,
            @JsonProperty("ExpiryDateTime") Instant expiryDateTime,
            @JsonProperty("DebtorAccount") DebtorAccount debtorAccount
    ) {

        public Data(String psuId, Limit limit, Instant expiryDateTime) {
            this(psuId, limit, expiryDateTime, null);
        }

        public String debtorAccountId() {
            return debtorAccount == null ? null : debtorAccount.identification();
        }
    }

    /** Optional PSU account the collections debit; verified with the accounts service. */
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
