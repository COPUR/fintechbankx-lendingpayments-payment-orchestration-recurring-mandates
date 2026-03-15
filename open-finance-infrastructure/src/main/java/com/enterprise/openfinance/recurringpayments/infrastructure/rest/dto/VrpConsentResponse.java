package com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record VrpConsentResponse(
        @JsonProperty("Data") Data data,
        @JsonProperty("Links") Links links,
        @JsonProperty("Meta") Meta meta
) {

    public static VrpConsentResponse from(VrpConsent consent) {
        return new VrpConsentResponse(
                new Data(
                        consent.consentId(),
                        consent.status().apiValue(),
                        new Amount(consent.maxAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(), consent.currency()),
                        consent.expiresAt().toString(),
                        consent.revokedAt() == null ? null : consent.revokedAt().toString()
                ),
                new Links("/open-finance/v1/vrp/payment-consents/" + consent.consentId()),
                new Meta()
        );
    }

    public record Data(
            @JsonProperty("ConsentId") String consentId,
            @JsonProperty("Status") String status,
            @JsonProperty("Limit") Amount limit,
            @JsonProperty("ExpiryDateTime") String expiryDateTime,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("RevokedAt") String revokedAt
    ) {
    }

    public record Amount(
            @JsonProperty("Amount") String amount,
            @JsonProperty("Currency") String currency
    ) {
    }

    public record Links(
            @JsonProperty("Self") String self
    ) {
    }

    public record Meta() {
    }
}
