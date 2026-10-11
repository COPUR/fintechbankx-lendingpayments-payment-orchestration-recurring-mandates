package com.enterprise.openfinance.recurringpayments.infrastructure.security;

import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Resolves the calling TPP. With a validated token the TPP is the token's
 * client (azp, else client_id); an x-fapi-financial-id header that names a
 * different TPP is refused with 403, so one TPP can never act on another's
 * mandates by changing a header. Without an authenticated token (unit tests
 * of the controller only; the security chain rejects such requests) the
 * header is used.
 */
public final class TppIdentity {

    private TppIdentity() {
    }

    public static String resolve(String financialIdHeader) {
        String header = financialIdHeader == null || financialIdHeader.isBlank() ? null : financialIdHeader.trim();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String client = jwt.getToken().getClaimAsString("azp");
            if (client == null || client.isBlank()) {
                client = jwt.getToken().getClaimAsString("client_id");
            }
            if (client == null || client.isBlank()) {
                throw new ForbiddenException("Token does not identify a TPP client");
            }
            if (header != null && !header.equals(client)) {
                throw new ForbiddenException("x-fapi-financial-id does not match the token's client");
            }
            return client;
        }
        if (header == null) {
            throw new ForbiddenException("TPP identity is required");
        }
        return header;
    }
}
