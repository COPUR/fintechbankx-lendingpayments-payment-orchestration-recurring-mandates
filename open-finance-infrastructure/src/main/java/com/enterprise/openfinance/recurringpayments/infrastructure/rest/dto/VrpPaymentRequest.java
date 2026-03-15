package com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VrpPaymentRequest(
        @JsonProperty("Data") Data data
) {

    public record Data(
            @JsonProperty("ConsentId") String consentId,
            @JsonProperty("InstructedAmount") Amount instructedAmount
    ) {
    }

    public record Amount(
            @JsonProperty("Amount") String amount,
            @JsonProperty("Currency") String currency
    ) {
    }
}
