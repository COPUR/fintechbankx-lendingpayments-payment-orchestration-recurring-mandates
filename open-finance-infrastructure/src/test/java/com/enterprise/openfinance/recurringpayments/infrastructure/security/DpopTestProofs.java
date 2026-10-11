package com.enterprise.openfinance.recurringpayments.infrastructure.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds RFC 9449 DPoP proofs and DPoP-bound access tokens for tests. */
public final class DpopTestProofs {

    private final ECKey key;

    public DpopTestProofs() {
        try {
            this.key = new ECKeyGenerator(Curve.P_256).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String thumbprint() {
        try {
            return key.computeThumbprint("SHA-256").toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** An access token issued to {@code client}, bound to this key (cnf.jkt). */
    public Jwt boundToken(String tokenValue, String client, Instant issuedAt) {
        return Jwt.withTokenValue(tokenValue).header("alg", "RS256")
                .claim("azp", client).audience(List.of("svc-pay-recurring-mandates"))
                .claim("cnf", Map.of("jkt", thumbprint()))
                .issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(300)).build();
    }

    public String proof(String method, String htu, String accessToken, Instant iat) {
        return proof(method, htu, accessToken, iat, UUID.randomUUID().toString());
    }

    public String proof(String method, String htu, String accessToken, Instant iat, String jti) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .jwtID(jti).claim("htm", method).claim("htu", htu).issueTime(Date.from(iat));
        if (accessToken != null) {
            claims.claim("ath", ath(accessToken));
        }
        return sign(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("dpop+jwt"))
                .jwk(key.toPublicJWK()).build(), claims.build(), key);
    }

    /** A proof with a custom header, claims and signing key, for negative tests. */
    public static String sign(JWSHeader header, JWTClaimsSet claims, ECKey signingKey) {
        try {
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new ECDSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public ECKey key() {
        return key;
    }

    public static String ath(String accessToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
