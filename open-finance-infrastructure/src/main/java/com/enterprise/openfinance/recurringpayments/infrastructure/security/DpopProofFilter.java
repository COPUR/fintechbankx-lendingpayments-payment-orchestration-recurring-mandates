package com.enterprise.openfinance.recurringpayments.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;

/**
 * DPoP for the TPP-facing VRP API (/open-finance/v1/vrp/**), after bearer
 * authentication. With dpop required (default) every request must use the
 * DPoP authorization scheme, a token bound with cnf.jkt and exactly one
 * valid proof; a plain Bearer token is 401. With dpop not required an
 * unbound Bearer token without a proof passes, but a bound token or a
 * proof is still verified fully. Unauthenticated requests are left to the
 * authorization rules (401).
 */
public class DpopProofFilter extends OncePerRequestFilter {

    static final String API_PREFIX = "/open-finance/v1/vrp/";

    private final DpopProofVerifier verifier;
    private final boolean required;

    public DpopProofFilter(DpopProofVerifier verifier, boolean required) {
        this.verifier = verifier;
        this.required = required;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            check(request, token.getToken());
        } catch (DpopProofException e) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void check(HttpServletRequest request, Jwt accessToken) {
        List<String> proofs = Collections.list(request.getHeaders("DPoP"));
        boolean bound = DpopProofVerifier.boundThumbprint(accessToken) != null;
        if (proofs.size() > 1) {
            throw new DpopProofException("More than one DPoP header");
        }
        if (proofs.isEmpty()) {
            if (required || bound) {
                throw new DpopProofException("DPoP proof is required");
            }
            return;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean dpopScheme = authorization != null && authorization.regionMatches(true, 0, "DPoP ", 0, 5);
        if ((required || bound) && !dpopScheme) {
            throw new DpopProofException("A DPoP-bound token must use the DPoP authorization scheme");
        }
        verifier.verify(proofs.get(0), request.getMethod(), URI.create(request.getRequestURL().toString()),
                accessToken);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "DPoP error=\"invalid_dpop_proof\", algs=\"ES256 PS256\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"INVALID_DPOP_PROOF\",\"message\":\"DPoP proof or token binding is invalid\"}");
    }
}
