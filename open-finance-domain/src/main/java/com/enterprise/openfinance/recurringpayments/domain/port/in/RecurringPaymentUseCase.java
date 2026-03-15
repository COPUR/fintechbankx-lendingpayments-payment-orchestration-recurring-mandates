package com.enterprise.openfinance.recurringpayments.domain.port.in;

import com.enterprise.openfinance.recurringpayments.domain.command.CreateVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.RevokeVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.SubmitVrpPaymentCommand;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpCollectionResult;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpConsentQuery;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpPaymentQuery;

import java.util.Optional;

public interface RecurringPaymentUseCase {

    VrpConsent createConsent(CreateVrpConsentCommand command);

    Optional<VrpConsent> getConsent(GetVrpConsentQuery query);

    void revokeConsent(RevokeVrpConsentCommand command);

    VrpCollectionResult submitCollection(SubmitVrpPaymentCommand command);

    Optional<VrpPayment> getPayment(GetVrpPaymentQuery query);
}
