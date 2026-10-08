package com.enterprise.openfinance.recurringpayments.infrastructure.security;

import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigurationTest {

    private static final String SERVICE = "svc-pay-recurring-mandates";
    private static final KeyPair KEYS = rsa();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tokenForThisServiceIsAcceptedAndATokenForAnotherAudienceIsRejected() throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(),
                SecurityConfiguration.audienceValidator(SERVICE)));

        assertThat(decoder.decode(token(List.of(SERVICE, "account"), "TPP-001")).getClaimAsString("azp")).isEqualTo("TPP-001");
        assertThatThrownBy(() -> decoder.decode(token(List.of("svc-pay-request-to-pay"), "TPP-001")))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("not issued for " + SERVICE);
        // This service's own client-credentials token names it in azp.
        assertThat(decoder.decode(token(List.of(), SERVICE)).getClaimAsString("azp")).isEqualTo(SERVICE);
    }

    @Test
    void realmRolesBecomeUpperCaseRoleAuthorities() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("service", "auditor"))));

        assertThat(SecurityConfiguration.realmRoles(jwt)).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SERVICE", "ROLE_AUDITOR");
        assertThat(SecurityConfiguration.realmRoles(jwt(Map.of("azp", "x")))).isEmpty();
        assertThat(SecurityConfiguration.keycloakRealmRoles().convert(jwt).getName()).isEqualTo("subject-1");
    }

    @Test
    void acceptsBearerAndDpopAuthorizationSchemes() {
        BearerTokenResolver resolver = SecurityConfiguration.bearerOrDpopTokenResolver();

        assertThat(resolver.resolve(withAuthorization("DPoP abc"))).isEqualTo("abc");
        assertThat(resolver.resolve(withAuthorization("Bearer xyz"))).isEqualTo("xyz");
        assertThat(resolver.resolve(withAuthorization("DPoP  "))).isNull();
        assertThat(resolver.resolve(new MockHttpServletRequest())).isNull();
    }

    @Test
    void tppIsTheTokenClientAndAMismatchingHeaderIsForbidden() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(Map.of("azp", "TPP-001"))));
        assertThat(TppIdentity.resolve(null)).isEqualTo("TPP-001");
        assertThat(TppIdentity.resolve(" TPP-001 ")).isEqualTo("TPP-001");
        assertThatThrownBy(() -> TppIdentity.resolve("TPP-002")).isInstanceOf(ForbiddenException.class);

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(Map.of("client_id", "TPP-003"))));
        assertThat(TppIdentity.resolve(null)).isEqualTo("TPP-003");

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(Map.of("scope", "x"))));
        assertThatThrownBy(() -> TppIdentity.resolve("TPP-001")).isInstanceOf(ForbiddenException.class);

        SecurityContextHolder.clearContext();
        assertThat(TppIdentity.resolve("TPP-009")).isEqualTo("TPP-009");
        assertThatThrownBy(() -> TppIdentity.resolve(" ")).isInstanceOf(ForbiddenException.class);
    }

    private static MockHttpServletRequest withAuthorization(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", value);
        return request;
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("subject-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static String token(List<String> audience, String azp) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("service-account")
                .audience(audience)
                .claim("azp", azp)
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(KEYS.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
