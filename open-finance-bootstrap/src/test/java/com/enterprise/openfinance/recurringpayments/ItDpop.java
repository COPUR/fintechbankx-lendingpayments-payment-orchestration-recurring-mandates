package com.enterprise.openfinance.recurringpayments;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/** The TPP's DPoP key in the integration tests: tokens are bound to it and proofs are signed with it. */
final class ItDpop {

    private static final ECKey KEY;

    static {
        try {
            KEY = new ECKeyGenerator(Curve.P_256).generate();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ItDpop() {
    }

    static String thumbprint() {
        try {
            return KEY.computeThumbprint("SHA-256").toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Adds Authorization "<scheme> <token>" and a fresh proof for the request's method and URL. */
    static RequestPostProcessor dpop(String scheme, String accessToken) {
        return request -> {
            request.addHeader("Authorization", scheme + " " + accessToken);
            request.addHeader("DPoP", proof(request.getMethod(), request.getRequestURL().toString(), accessToken));
            return request;
        };
    }

    static String proof(String method, String htu, String accessToken) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().jwtID(UUID.randomUUID().toString())
                    .claim("htm", method).claim("htu", htu).claim("ath", ath(accessToken))
                    .issueTime(new Date()).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
                    .type(new JOSEObjectType("dpop+jwt")).jwk(KEY.toPublicJWK()).build(), claims);
            jwt.sign(new ECDSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ath(String accessToken) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}
