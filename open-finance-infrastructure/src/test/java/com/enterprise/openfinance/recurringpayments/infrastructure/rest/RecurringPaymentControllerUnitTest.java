package com.enterprise.openfinance.recurringpayments.infrastructure.rest;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpCollectionResult;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsentStatus;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPaymentStatus;
import com.enterprise.openfinance.recurringpayments.domain.port.in.RecurringPaymentUseCase;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpConsentQuery;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpPaymentQuery;
import com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto.VrpConsentRequest;
import com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto.VrpConsentResponse;
import com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto.VrpPaymentRequest;
import com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto.VrpPaymentResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class RecurringPaymentControllerUnitTest {

    @Test
    void shouldCreateConsentAndSubmitPayment() {
        RecurringPaymentUseCase useCase = Mockito.mock(RecurringPaymentUseCase.class);
        RecurringPaymentController controller = new RecurringPaymentController(useCase);

        Mockito.when(useCase.createConsent(Mockito.any())).thenReturn(consent("CONS-VRP-001"));
        Mockito.when(useCase.submitCollection(Mockito.any())).thenReturn(new VrpCollectionResult(
                "PAY-VRP-001",
                "CONS-VRP-001",
                VrpPaymentStatus.ACCEPTED,
                "ix-1",
                Instant.parse("2026-02-09T10:00:00Z"),
                false
        ));

        ResponseEntity<VrpConsentResponse> consentResponse = controller.createConsent(
                "DPoP token",
                "proof",
                "ix-1",
                "TPP-001",
                consentRequest("PSU-001", "5000.00", "AED", "2099-01-01T00:00:00Z")
        );

        ResponseEntity<VrpPaymentResponse> paymentResponse = controller.submitPayment(
                "DPoP token",
                "proof",
                "ix-1",
                "TPP-001",
                "IDEMP-001",
                paymentRequest("CONS-VRP-001", "100.00", "AED")
        );

        assertThat(consentResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(consentResponse.getHeaders().getLocation()).hasToString("/open-finance/v1/vrp/payment-consents/CONS-VRP-001");
        assertThat(paymentResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(paymentResponse.getHeaders().getFirst("X-OF-Idempotency")).isEqualTo("MISS");

        ArgumentCaptor<GetVrpConsentQuery> consentQueryCaptor = ArgumentCaptor.forClass(GetVrpConsentQuery.class);
        Mockito.when(useCase.getConsent(Mockito.any())).thenReturn(Optional.of(consent("CONS-VRP-001")));
        controller.getConsent("DPoP token", "proof", "ix-2", "TPP-001", "CONS-VRP-001", null);
        Mockito.verify(useCase).getConsent(consentQueryCaptor.capture());
        assertThat(consentQueryCaptor.getValue().consentId()).isEqualTo("CONS-VRP-001");
    }

    @Test
    void shouldReturnConsentAndPaymentWithEtagAndNotModified() {
        RecurringPaymentUseCase useCase = Mockito.mock(RecurringPaymentUseCase.class);
        RecurringPaymentController controller = new RecurringPaymentController(useCase);

        Mockito.when(useCase.getConsent(Mockito.any())).thenReturn(Optional.of(consent("CONS-VRP-001")));
        Mockito.when(useCase.getPayment(Mockito.any())).thenReturn(Optional.of(payment("PAY-VRP-001")));

        ResponseEntity<VrpConsentResponse> firstConsent = controller.getConsent(
                "DPoP token",
                "proof",
                "ix-3",
                "TPP-001",
                "CONS-VRP-001",
                null
        );

        ResponseEntity<VrpPaymentResponse> firstPayment = controller.getPayment(
                "DPoP token",
                "proof",
                "ix-3",
                "TPP-001",
                "PAY-VRP-001",
                null
        );

        ResponseEntity<VrpConsentResponse> secondConsent = controller.getConsent(
                "DPoP token",
                "proof",
                "ix-3",
                "TPP-001",
                "CONS-VRP-001",
                firstConsent.getHeaders().getETag()
        );

        ResponseEntity<VrpPaymentResponse> secondPayment = controller.getPayment(
                "DPoP token",
                "proof",
                "ix-3",
                "TPP-001",
                "PAY-VRP-001",
                firstPayment.getHeaders().getETag()
        );

        assertThat(firstConsent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstPayment.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondConsent.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(secondPayment.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        ArgumentCaptor<GetVrpPaymentQuery> paymentQueryCaptor = ArgumentCaptor.forClass(GetVrpPaymentQuery.class);
        Mockito.verify(useCase).getPayment(paymentQueryCaptor.capture());
        assertThat(paymentQueryCaptor.getValue().paymentId()).isEqualTo("PAY-VRP-001");
    }

    @Test
    void shouldRevokeConsentAndReturnNoContent() {
        RecurringPaymentUseCase useCase = Mockito.mock(RecurringPaymentUseCase.class);
        RecurringPaymentController controller = new RecurringPaymentController(useCase);

        ResponseEntity<Void> response = controller.revokeConsent(
                "DPoP token",
                "proof",
                "ix-4",
                "TPP-001",
                "CONS-VRP-001",
                "User request"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void shouldRejectUnsupportedAuthorizationType() {
        RecurringPaymentUseCase useCase = Mockito.mock(RecurringPaymentUseCase.class);
        RecurringPaymentController controller = new RecurringPaymentController(useCase);

        assertThatThrownBy(() -> controller.createConsent(
                "Basic token",
                "proof",
                "ix-1",
                "TPP-001",
                consentRequest("PSU-001", "5000.00", "AED", "2099-01-01T00:00:00Z")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bearer or DPoP");
    }

    private static VrpConsent consent(String consentId) {
        return new VrpConsent(
                consentId,
                "TPP-001",
                "PSU-001",
                new BigDecimal("5000.00"),
                "AED",
                VrpConsentStatus.AUTHORISED,
                Instant.parse("2099-01-01T00:00:00Z"),
                null
        );
    }

    private static VrpPayment payment(String paymentId) {
        return new VrpPayment(
                paymentId,
                "CONS-VRP-001",
                "TPP-001",
                "IDEMP-001",
                new BigDecimal("100.00"),
                "AED",
                "2026-02",
                VrpPaymentStatus.ACCEPTED,
                Instant.parse("2026-02-09T10:00:00Z")
        );
    }

    private static VrpConsentRequest consentRequest(String psuId, String amount, String currency, String expiresAt) {
        return new VrpConsentRequest(new VrpConsentRequest.Data(
                psuId,
                new VrpConsentRequest.Limit(amount, currency),
                Instant.parse(expiresAt)
        ));
    }

    private static VrpPaymentRequest paymentRequest(String consentId, String amount, String currency) {
        return new VrpPaymentRequest(new VrpPaymentRequest.Data(
                consentId,
                new VrpPaymentRequest.Amount(amount, currency)
        ));
    }
}
