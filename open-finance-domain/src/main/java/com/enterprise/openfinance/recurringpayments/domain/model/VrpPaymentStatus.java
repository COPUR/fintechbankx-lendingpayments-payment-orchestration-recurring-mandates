package com.enterprise.openfinance.recurringpayments.domain.model;

public enum VrpPaymentStatus {
    ACCEPTED("Accepted"),
    REJECTED("Rejected");

    private final String apiValue;

    VrpPaymentStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }
}
