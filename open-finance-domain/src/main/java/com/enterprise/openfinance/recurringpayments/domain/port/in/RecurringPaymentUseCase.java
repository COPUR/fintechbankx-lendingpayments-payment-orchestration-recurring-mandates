package com.enterprise.openfinance.recurringpayments.domain.port.in;

import com.enterprise.openfinance.recurringpayments.domain.command.CreateVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.RevokeVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.SubmitVrpPaymentCommand;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpCollectionResult;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpConsentQuery;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpPaymentQuery;

public interface RecurringPaymentUseCase {

    VrpConsent createConsent(CreateVrpConsentCommand command);

    /** Throws ConsentNotUsableException for an unknown mandate or another TPP's (one 403). */
    VrpConsent getConsent(GetVrpConsentQuery query);

    void revokeConsent(RevokeVrpConsentCommand command);

    VrpCollectionResult submitCollection(SubmitVrpPaymentCommand command);

    /** Throws PaymentNotAccessibleException for an unknown payment or another TPP's (one 403). */
    VrpPayment getPayment(GetVrpPaymentQuery query);
}
