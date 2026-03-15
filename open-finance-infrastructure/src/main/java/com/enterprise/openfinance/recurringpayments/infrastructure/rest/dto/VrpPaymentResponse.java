package com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpCollectionResult;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.fasterxml.jackson.annotation.JsonProperty;

public record VrpPaymentResponse(
        @JsonProperty("Data") Data data,
        @JsonProperty("Links") Links links,
        @JsonProperty("Meta") Meta meta
) {

    public static VrpPaymentResponse from(VrpCollectionResult result, String amount, String currency) {
        return new VrpPaymentResponse(
                new Data(
                        result.paymentId(),
                        result.consentId(),
                        result.status().apiValue(),
                        new Amount(amount, currency),
                        result.createdAt().toString()
                ),
                new Links("/open-finance/v1/vrp/payments/" + result.paymentId()),
                new Meta(result.idempotencyReplay())
        );
    }

    public static VrpPaymentResponse from(VrpPayment payment) {
        return new VrpPaymentResponse(
                new Data(
                        payment.paymentId(),
                        payment.consentId(),
                        payment.status().apiValue(),
                        new Amount(payment.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(), payment.currency()),
                        payment.createdAt().toString()
                ),
                new Links("/open-finance/v1/vrp/payments/" + payment.paymentId()),
                new Meta(false)
        );
    }

    public record Data(
            @JsonProperty("PaymentId") String paymentId,
            @JsonProperty("ConsentId") String consentId,
            @JsonProperty("Status") String status,
            @JsonProperty("InstructedAmount") Amount instructedAmount,
            @JsonProperty("CreationDateTime") String creationDateTime
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

    public record Meta(
            @JsonProperty("IdempotencyReplay") boolean idempotencyReplay
    ) {
    }
}
