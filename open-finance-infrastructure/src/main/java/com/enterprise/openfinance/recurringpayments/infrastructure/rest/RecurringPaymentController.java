package com.enterprise.openfinance.recurringpayments.infrastructure.rest;

import com.enterprise.openfinance.recurringpayments.domain.command.CreateVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.RevokeVrpConsentCommand;
import com.enterprise.openfinance.recurringpayments.domain.command.SubmitVrpPaymentCommand;
import com.enterprise.openfinance.recurringpayments.domain.port.in.RecurringPaymentUseCase;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpConsentQuery;
import com.enterprise.openfinance.recurringpayments.domain.query.GetVrpPaymentQuery;
import com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto.VrpConsentRequest;
import com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto.VrpConsentResponse;
import com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto.VrpPaymentRequest;
import com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto.VrpPaymentResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@RestController
@Validated
@RequestMapping("/open-finance/v1/vrp")
public class RecurringPaymentController {

    private final RecurringPaymentUseCase useCase;
    private final Map<String, String> consentEtagCache = new ConcurrentHashMap<>();
    private final Map<String, String> paymentEtagCache = new ConcurrentHashMap<>();

    public RecurringPaymentController(RecurringPaymentUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/payment-consents")
    public ResponseEntity<VrpConsentResponse> createConsent(
            @RequestHeader("Authorization") @NotBlank String authorization,
            @RequestHeader("DPoP") @NotBlank String dpop,
            @RequestHeader("X-FAPI-Interaction-ID") @NotBlank String interactionId,
            @RequestHeader(value = "x-fapi-financial-id", required = false) String financialId,
            @RequestBody VrpConsentRequest request
    ) {
        validateSecurityHeaders(authorization, dpop, interactionId);
        String tppId = resolveTppId(financialId);

        var command = new CreateVrpConsentCommand(
                tppId,
                request.data().psuId(),
                new BigDecimal(request.data().limit().amount()),
                request.data().limit().currency(),
                request.data().expiryDateTime(),
                interactionId
        );

        var consent = useCase.createConsent(command);
        return ResponseEntity.created(URI.create("/open-finance/v1/vrp/payment-consents/" + consent.consentId()))
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                .header("X-FAPI-Interaction-ID", interactionId)
                .body(VrpConsentResponse.from(consent));
    }

    @GetMapping("/payment-consents/{consentId}")
    public ResponseEntity<VrpConsentResponse> getConsent(
            @RequestHeader("Authorization") @NotBlank String authorization,
            @RequestHeader("DPoP") @NotBlank String dpop,
            @RequestHeader("X-FAPI-Interaction-ID") @NotBlank String interactionId,
            @RequestHeader(value = "x-fapi-financial-id", required = false) String financialId,
            @PathVariable @NotBlank String consentId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        validateSecurityHeaders(authorization, dpop, interactionId);
        String tppId = resolveTppId(financialId);

        String requestKey = consentRequestKey(consentId, tppId);
        String cachedEtag = consentEtagCache.get(requestKey);
        if (ifNoneMatch != null && ifNoneMatch.equals(cachedEtag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                    .header("X-FAPI-Interaction-ID", interactionId)
                    .eTag(cachedEtag)
                    .build();
        }

        boolean cacheHit = cachedEtag != null;
        Optional<VrpConsentResponse> response = useCase.getConsent(new GetVrpConsentQuery(consentId, tppId, interactionId))
                .map(VrpConsentResponse::from);

        if (response.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                    .header("X-FAPI-Interaction-ID", interactionId)
                    .build();
        }

        String etag = generateEtag(response.orElseThrow().data().toString());
        consentEtagCache.put(requestKey, etag);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                .header("X-FAPI-Interaction-ID", interactionId)
                .header("X-OF-Cache", cacheHit ? "HIT" : "MISS")
                .eTag(etag)
                .body(response.orElseThrow());
    }

    @DeleteMapping("/payment-consents/{consentId}")
    public ResponseEntity<Void> revokeConsent(
            @RequestHeader("Authorization") @NotBlank String authorization,
            @RequestHeader("DPoP") @NotBlank String dpop,
            @RequestHeader("X-FAPI-Interaction-ID") @NotBlank String interactionId,
            @RequestHeader(value = "x-fapi-financial-id", required = false) String financialId,
            @PathVariable @NotBlank String consentId,
            @RequestParam("reason") String reason
    ) {
        validateSecurityHeaders(authorization, dpop, interactionId);
        String tppId = resolveTppId(financialId);

        useCase.revokeConsent(new RevokeVrpConsentCommand(consentId, tppId, interactionId, reason));

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                .header("X-FAPI-Interaction-ID", interactionId)
                .build();
    }

    @PostMapping("/payments")
    public ResponseEntity<VrpPaymentResponse> submitPayment(
            @RequestHeader("Authorization") @NotBlank String authorization,
            @RequestHeader("DPoP") @NotBlank String dpop,
            @RequestHeader("X-FAPI-Interaction-ID") @NotBlank String interactionId,
            @RequestHeader(value = "x-fapi-financial-id", required = false) String financialId,
            @RequestHeader("x-idempotency-key") @NotBlank String idempotencyKey,
            @RequestBody VrpPaymentRequest request
    ) {
        validateSecurityHeaders(authorization, dpop, interactionId);
        String tppId = resolveTppId(financialId);

        var command = new SubmitVrpPaymentCommand(
                tppId,
                request.data().consentId(),
                idempotencyKey,
                new BigDecimal(request.data().instructedAmount().amount()),
                request.data().instructedAmount().currency(),
                interactionId
        );

        var result = useCase.submitCollection(command);
        VrpPaymentResponse response = VrpPaymentResponse.from(
                result,
                request.data().instructedAmount().amount(),
                request.data().instructedAmount().currency()
        );

        return ResponseEntity.created(URI.create("/open-finance/v1/vrp/payments/" + result.paymentId()))
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                .header("X-FAPI-Interaction-ID", interactionId)
                .header("X-Idempotency-Key", idempotencyKey)
                .header("X-OF-Idempotency", result.idempotencyReplay() ? "HIT" : "MISS")
                .body(response);
    }

    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<VrpPaymentResponse> getPayment(
            @RequestHeader("Authorization") @NotBlank String authorization,
            @RequestHeader("DPoP") @NotBlank String dpop,
            @RequestHeader("X-FAPI-Interaction-ID") @NotBlank String interactionId,
            @RequestHeader(value = "x-fapi-financial-id", required = false) String financialId,
            @PathVariable @NotBlank String paymentId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        validateSecurityHeaders(authorization, dpop, interactionId);
        String tppId = resolveTppId(financialId);

        String requestKey = paymentRequestKey(paymentId, tppId);
        String cachedEtag = paymentEtagCache.get(requestKey);
        if (ifNoneMatch != null && ifNoneMatch.equals(cachedEtag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                    .header("X-FAPI-Interaction-ID", interactionId)
                    .eTag(cachedEtag)
                    .build();
        }

        boolean cacheHit = cachedEtag != null;
        Optional<VrpPaymentResponse> response = useCase.getPayment(new GetVrpPaymentQuery(paymentId, tppId, interactionId))
                .map(VrpPaymentResponse::from);

        if (response.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                    .header("X-FAPI-Interaction-ID", interactionId)
                    .build();
        }

        String etag = generateEtag(response.orElseThrow().data().toString());
        paymentEtagCache.put(requestKey, etag);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore())
                .header("X-FAPI-Interaction-ID", interactionId)
                .header("X-OF-Cache", cacheHit ? "HIT" : "MISS")
                .eTag(etag)
                .body(response.orElseThrow());
    }

    private static String resolveTppId(String financialId) {
        if (financialId == null || financialId.isBlank()) {
            return "UNKNOWN_TPP";
        }
        return financialId.trim();
    }

    private static void validateSecurityHeaders(String authorization,
                                                String dpop,
                                                String interactionId) {
        boolean validAuthorization = authorization.startsWith("DPoP ") || authorization.startsWith("Bearer ");
        if (!validAuthorization) {
            throw new IllegalArgumentException("Authorization header must use Bearer or DPoP token type");
        }
        if (dpop.isBlank()) {
            throw new IllegalArgumentException("DPoP header is required");
        }
        if (interactionId.isBlank()) {
            throw new IllegalArgumentException("X-FAPI-Interaction-ID header is required");
        }
    }

    private static String generateEtag(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return '"' + Base64.getUrlEncoder().withoutPadding().encodeToString(hash) + '"';
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to generate ETag", exception);
        }
    }

    private static String consentRequestKey(String consentId, String tppId) {
        return "consent:" + consentId + ':' + tppId;
    }

    private static String paymentRequestKey(String paymentId, String tppId) {
        return "payment:" + paymentId + ':' + tppId;
    }
}
