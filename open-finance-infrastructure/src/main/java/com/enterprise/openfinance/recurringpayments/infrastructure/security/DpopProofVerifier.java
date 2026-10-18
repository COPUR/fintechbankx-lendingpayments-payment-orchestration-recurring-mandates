package com.enterprise.openfinance.recurringpayments.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Verifies a DPoP proof (RFC 9449 section 4.3) for one request and the
 * access token it accompanies: typ dpop+jwt, alg ES256 or PS256, a public
 * jwk in the header (Nimbus refuses a private jwk while parsing) that verifies the signature, htm and htu matching the
 * request, iat inside [now - maxAge, now + clockSkew], ath = hash of the
 * access token, the token's cnf.jkt = thumbprint of the proof key, and a
 * jti not seen before for that key. The jti is claimed last, so a rejected
 * proof never uses up a jti.
 */
public class DpopProofVerifier {

    private static final JOSEObjectType DPOP_TYPE = new JOSEObjectType("dpop+jwt");
    static final int MAX_JTI_LENGTH = 128;
    private static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.ES256, JWSAlgorithm.PS256);

    private final DpopReplayStore replayStore;
    private final Clock clock;
    private final Duration maxAge;
    private final Duration clockSkew;

    public DpopProofVerifier(DpopReplayStore replayStore, Clock clock, Duration maxAge, Duration clockSkew) {
        this.replayStore = replayStore;
        this.clock = clock;
        this.maxAge = maxAge;
        this.clockSkew = clockSkew;
    }

    /** The cnf.jkt of a DPoP-bound access token, or null for an unbound token. */
    public static String boundThumbprint(Jwt accessToken) {
        Object cnf = accessToken.getClaims().get("cnf");
        if (cnf instanceof Map<?, ?> map && map.get("jkt") instanceof String jkt && !jkt.isBlank()) {
            return jkt;
        }
        return null;
    }

    public void verify(String proof, String method, URI requestUri, Jwt accessToken) {
        SignedJWT jwt = parse(proof);
        JWK key = verifyHeaderAndSignature(jwt);
        JWTClaimsSet claims = claims(jwt);

        requireEqual("htm", method, stringClaim(claims, "htm"));
        if (!normalise(requestUri).equals(normalise(uri(stringClaim(claims, "htu"))))) {
            throw new DpopProofException("DPoP proof htu does not match the request URI");
        }
        Instant iat = issuedAt(claims);
        requireEqual("ath", accessTokenHash(accessToken.getTokenValue()), stringClaim(claims, "ath"));

        String bound = boundThumbprint(accessToken);
        if (bound == null) {
            throw new DpopProofException("Access token is not DPoP-bound (no cnf.jkt)");
        }
        String thumbprint = thumbprint(key);
        if (!bound.equals(thumbprint)) {
            throw new DpopProofException("DPoP proof key does not match the access token cnf.jkt");
        }

        String jti = claims.getJWTID();
        if (jti == null || jti.isBlank() || jti.length() > MAX_JTI_LENGTH) {
            throw new DpopProofException("DPoP proof jti is missing or longer than " + MAX_JTI_LENGTH);
        }
        if (!replayStore.claim(thumbprint, jti, iat.plus(maxAge).plus(clockSkew))) {
            throw new DpopProofException("DPoP proof jti was already used");
        }
    }

    private static SignedJWT parse(String proof) {
        if (proof == null || proof.isBlank()) {
            throw new DpopProofException("DPoP proof is missing");
        }
        try {
            return SignedJWT.parse(proof.trim());
        } catch (ParseException e) {
            throw new DpopProofException("DPoP proof is not a signed JWT", e);
        }
    }

    private static JWK verifyHeaderAndSignature(SignedJWT jwt) {
        JWSHeader header = jwt.getHeader();
        if (!DPOP_TYPE.equals(header.getType())) {
            throw new DpopProofException("DPoP proof typ must be dpop+jwt");
        }
        if (!ALGORITHMS.contains(header.getAlgorithm())) {
            throw new DpopProofException("DPoP proof alg must be ES256 or PS256");
        }
        JWK key = header.getJWK();
        if (key == null) {
            throw new DpopProofException("DPoP proof header has no jwk");
        }
        try {
            JWSVerifier verifier = switch (key) {
                case ECKey ec -> new ECDSAVerifier(ec);
                case RSAKey rsa -> new RSASSAVerifier(rsa);
                default -> throw new DpopProofException("DPoP proof jwk type is not supported");
            };
            if (!jwt.verify(verifier)) {
                throw new DpopProofException("DPoP proof signature is invalid");
            }
        } catch (JOSEException e) {
            throw new DpopProofException("DPoP proof signature is invalid", e);
        }
        return key;
    }

    private Instant issuedAt(JWTClaimsSet claims) {
        if (claims.getIssueTime() == null) {
            throw new DpopProofException("DPoP proof iat is missing");
        }
        Instant iat = claims.getIssueTime().toInstant();
        Instant now = clock.instant();
        if (iat.isBefore(now.minus(maxAge)) || iat.isAfter(now.plus(clockSkew))) {
            throw new DpopProofException("DPoP proof iat is outside the accepted window");
        }
        return iat;
    }

    private static JWTClaimsSet claims(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new DpopProofException("DPoP proof claims cannot be read", e);
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        try {
            return claims.getStringClaim(name);
        } catch (ParseException e) {
            throw new DpopProofException("DPoP proof " + name + " must be a string", e);
        }
    }

    private static void requireEqual(String claim, String expected, String actual) {
        if (actual == null || !actual.equals(expected)) {
            throw new DpopProofException("DPoP proof " + claim + " does not match the request");
        }
    }

    private static URI uri(String value) {
        if (value == null) {
            throw new DpopProofException("DPoP proof htu is missing");
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new DpopProofException("DPoP proof htu is not a URI", e);
        }
    }

    /** scheme and host lower-case, default port dropped, no query or fragment (RFC 9449 section 4.3). */
    static String normalise(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return scheme + "://" + host + (defaultPort ? "" : ":" + port) + path;
    }

    static String accessTokenHash(String accessToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String thumbprint(JWK key) {
        try {
            return key.computeThumbprint("SHA-256").toString();
        } catch (JOSEException e) {
            throw new DpopProofException("DPoP proof jwk thumbprint cannot be computed", e);
        }
    }
}
