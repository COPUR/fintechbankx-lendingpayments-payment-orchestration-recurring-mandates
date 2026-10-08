package com.enterprise.openfinance.recurringpayments.infrastructure.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DpopProofFilterTest {

    private final Instant now = Instant.now();
    private final DpopTestProofs tpp = new DpopTestProofs();
    private final Set<String> claimed = new HashSet<>();
    private final DpopProofVerifier verifier = new DpopProofVerifier((jkt, jti, exp) -> claimed.add(jti),
            Clock.systemUTC(), Duration.ofMinutes(5), Duration.ofSeconds(30));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requiredModePassesAValidProofAndRefusesAMissingOne() throws Exception {
        authenticate(tpp.boundToken("tok-1", "TPP-001", now));

        MockHttpServletResponse ok = run(new DpopProofFilter(verifier, true),
                request("DPoP tok-1", tpp.proof("GET", "http://localhost/open-finance/v1/vrp/payments/P-1", "tok-1", now)));
        assertThat(ok.getStatus()).isEqualTo(200);

        MockHttpServletResponse missing = run(new DpopProofFilter(verifier, true), request("DPoP tok-1", null));
        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(missing.getHeader("WWW-Authenticate")).startsWith("DPoP error=\"invalid_dpop_proof\"");
        assertThat(missing.getContentAsString()).contains("INVALID_DPOP_PROOF");
    }

    @Test
    void requiredModeRefusesAnUnboundTokenAndABoundTokenSentAsBearer() throws Exception {
        authenticate(Jwt.withTokenValue("tok-1").header("alg", "RS256").claim("azp", "TPP-001")
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build());
        assertThat(run(new DpopProofFilter(verifier, true), request("DPoP tok-1", null)).getStatus()).isEqualTo(401);

        authenticate(tpp.boundToken("tok-1", "TPP-001", now));
        String proof = tpp.proof("GET", "http://localhost/open-finance/v1/vrp/payments/P-1", "tok-1", now);
        assertThat(run(new DpopProofFilter(verifier, true), request("Bearer tok-1", proof)).getStatus()).isEqualTo(401);
    }

    @Test
    void optionalModeLetsAnUnboundBearerTokenThroughButStillChecksBoundTokensAndProofs() throws Exception {
        authenticate(Jwt.withTokenValue("tok-1").header("alg", "RS256").claim("azp", "TPP-001")
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build());
        assertThat(run(new DpopProofFilter(verifier, false), request("Bearer tok-1", null)).getStatus()).isEqualTo(200);
        assertThat(run(new DpopProofFilter(verifier, false), request("Bearer tok-1", "garbage")).getStatus()).isEqualTo(401);

        authenticate(tpp.boundToken("tok-1", "TPP-001", now));
        assertThat(run(new DpopProofFilter(verifier, false), request("DPoP tok-1", null)).getStatus()).isEqualTo(401);
    }

    @Test
    void onlyTheVrpApiIsFilteredAndAnonymousRequestsAreLeftToAuthorization() throws Exception {
        MockHttpServletRequest actuator = new MockHttpServletRequest("GET", "/actuator/health");
        assertThat(run(new DpopProofFilter(verifier, true), actuator).getStatus()).isEqualTo(200);
        assertThat(run(new DpopProofFilter(verifier, true), request(null, null)).getStatus()).isEqualTo(200);
    }

    @Test
    void twoProofHeadersAreRefused() throws Exception {
        authenticate(tpp.boundToken("tok-1", "TPP-001", now));
        MockHttpServletRequest request = request("DPoP tok-1", "a");
        request.addHeader("DPoP", "b");
        assertThat(run(new DpopProofFilter(verifier, true), request).getStatus()).isEqualTo(401);
    }

    private static MockHttpServletRequest request(String authorization, String proof) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/open-finance/v1/vrp/payments/P-1");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        if (proof != null) {
            request.addHeader("DPoP", proof);
        }
        return request;
    }

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static MockHttpServletResponse run(DpopProofFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
