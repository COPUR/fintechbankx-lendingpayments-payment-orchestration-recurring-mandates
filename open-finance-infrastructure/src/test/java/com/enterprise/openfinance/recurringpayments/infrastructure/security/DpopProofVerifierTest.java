package com.enterprise.openfinance.recurringpayments.infrastructure.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RFC 9449 proof checks: typ, alg, jwk, signature, htm, htu, iat, ath, cnf.jkt and jti replay. */
class DpopProofVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-08T07:00:00Z");
    private static final String HTU = "https://api.fintechbankx.example/open-finance/v1/vrp/payments";
    private static final URI REQUEST_URI = URI.create(HTU + "?ignored=query");

    private final DpopTestProofs tpp = new DpopTestProofs();
    private final Jwt token = tpp.boundToken("access-token-1", "TPP-001", NOW);
    private final Set<String> claimed = new HashSet<>();
    private final DpopProofVerifier verifier = new DpopProofVerifier(
            (jkt, jti, expiresAt) -> claimed.add(jkt + '|' + jti),
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5), Duration.ofSeconds(30));

    @Test
    void acceptsAFreshProofBoundToTheTokenAndRefusesItsReplay() {
        String proof = tpp.proof("POST", HTU, "access-token-1", NOW.minusSeconds(10));

        assertThatCode(() -> verifier.verify(proof, "POST", REQUEST_URI, token)).doesNotThrowAnyException();
        assertThatThrownBy(() -> verifier.verify(proof, "POST", REQUEST_URI, token))
                .isInstanceOf(DpopProofException.class).hasMessageContaining("jti");
    }

    @Test
    void htuIsComparedWithoutQueryCaseInsensitiveHostAndDefaultPort() {
        String proof = tpp.proof("POST", "https://API.fintechbankx.example:443/open-finance/v1/vrp/payments",
                "access-token-1", NOW);
        assertThatCode(() -> verifier.verify(proof, "POST", REQUEST_URI, token)).doesNotThrowAnyException();
    }

    @Test
    void refusesAProofForAnotherMethodOrUri() {
        assertRejected(tpp.proof("GET", HTU, "access-token-1", NOW), "htm");
        assertRejected(tpp.proof("POST", HTU.replace("/payments", "/payment-consents"), "access-token-1", NOW), "htu");
    }

    @Test
    void refusesAProofThatIsTooOldOrFromTheFuture() {
        assertRejected(tpp.proof("POST", HTU, "access-token-1", NOW.minus(Duration.ofMinutes(6))), "iat");
        assertRejected(tpp.proof("POST", HTU, "access-token-1", NOW.plusSeconds(31)), "iat");
    }

    @Test
    void refusesAProofWithoutOrWithAWrongAccessTokenHash() {
        assertRejected(tpp.proof("POST", HTU, null, NOW), "ath");
        assertRejected(tpp.proof("POST", HTU, "another-token", NOW), "ath");
    }

    @Test
    void refusesAProofFromAKeyTheTokenIsNotBoundTo() {
        DpopTestProofs attacker = new DpopTestProofs();
        assertRejected(attacker.proof("POST", HTU, "access-token-1", NOW), "jkt");

        Jwt unbound = Jwt.withTokenValue("access-token-1").header("alg", "RS256").claim("azp", "TPP-001")
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
        String proof = tpp.proof("POST", HTU, "access-token-1", NOW);
        assertThatThrownBy(() -> verifier.verify(proof, "POST", REQUEST_URI, unbound))
                .isInstanceOf(DpopProofException.class).hasMessageContaining("cnf");
    }

    @Test
    void refusesABadSignatureWrongTypUnsupportedAlgOrMissingJwk() throws Exception {
        var otherKey = new ECKeyGenerator(Curve.P_256).generate();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().jwtID("j-1").claim("htm", "POST").claim("htu", HTU)
                .claim("ath", DpopTestProofs.ath("access-token-1")).issueTime(Date.from(NOW)).build();

        // Header names the TPP key, but the proof is signed by another key.
        assertRejected(DpopTestProofs.sign(new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("dpop+jwt")).jwk(tpp.key().toPublicJWK()).build(), claims, otherKey), "signature");
        assertRejected(DpopTestProofs.sign(new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType.JWT).jwk(tpp.key().toPublicJWK()).build(), claims, tpp.key()), "typ");
        assertRejected(DpopTestProofs.sign(new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("dpop+jwt")).build(), claims, tpp.key()), "jwk");
        assertRejected(DpopTestProofs.sign(new JWSHeader.Builder(JWSAlgorithm.ES384)
                .type(new JOSEObjectType("dpop+jwt")).jwk(tpp.key().toPublicJWK()).build(), claims,
                new ECKeyGenerator(Curve.P_384).generate()), "alg");
        assertRejected("not-a-jwt", "proof");
        assertRejected(" ", "proof");
    }

    @Test
    void refusesAProofThatCarriesAPrivateKeyOrHasNoJti() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().jwtID("j-2").claim("htm", "POST").claim("htu", HTU)
                .claim("ath", DpopTestProofs.ath("access-token-1")).issueTime(Date.from(NOW)).build();
        // Nimbus refuses to build such a header, so it is written by hand; parsing refuses it.
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"typ\":\"dpop+jwt\",\"alg\":\"ES256\",\"jwk\":" + tpp.key().toJSONString() + "}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String signed = DpopTestProofs.sign(new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("dpop+jwt")).jwk(tpp.key().toPublicJWK()).build(), claims, tpp.key());
        assertRejected(header + signed.substring(signed.indexOf('.')), "proof");

        JWTClaimsSet noJti = new JWTClaimsSet.Builder().claim("htm", "POST").claim("htu", HTU)
                .claim("ath", DpopTestProofs.ath("access-token-1")).issueTime(Date.from(NOW)).build();
        assertRejected(DpopTestProofs.sign(new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("dpop+jwt")).jwk(tpp.key().toPublicJWK()).build(), noJti, tpp.key()), "jti");
        assertRejected(tpp.proof("POST", HTU, "access-token-1", NOW, "j".repeat(129)), "jti");
        assertThat(claimed).isEmpty();
    }

    private void assertRejected(String proof, String reason) {
        assertThatThrownBy(() -> verifier.verify(proof, "POST", REQUEST_URI, token))
                .isInstanceOf(DpopProofException.class)
                .hasMessageContaining(reason);
    }
}
