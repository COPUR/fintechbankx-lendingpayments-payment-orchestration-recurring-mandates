package com.enterprise.openfinance.recurringpayments.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Stateless OAuth2 resource server for the TPP-facing VRP API. Tokens come
 * from the platform Keycloak realm and must name this service in aud (or azp)
 * (OIDC_AUDIENCE, default the service id). The TPP is the token's azp
 * (client id), see {@link TppIdentity}. Realm roles become ROLE_* authorities.
 * Actuator endpoints are served on the management port (8081).
 *
 * DPoP: the API keeps the DPoP header required (FAPI contract) and accepts
 * the "DPoP" authorization scheme, but the proof is not validated here
 * (platform decision 2026-10-08 for lending and payments services).
 */
@Configuration
public class SecurityConfiguration {

    public static final String SERVICE_ID = "svc-pay-recurring-mandates";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/open-finance/v1/vrp/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerOrDpopTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRealmRoles())));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties,
                          @Value("${mandates.security.audience:" + SERVICE_ID + "}") String audience) {
        OAuth2ResourceServerProperties.Jwt jwt = properties.getJwt();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri()).build();
        OAuth2TokenValidator<Jwt> issuer = jwt.getIssuerUri() == null
                ? JwtValidators.createDefault()
                : JwtValidators.createDefaultWithIssuer(jwt.getIssuerUri());
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audienceValidator(audience)));
        return decoder;
    }

    /** Accepts "Authorization: Bearer x" and "Authorization: DPoP x". */
    static BearerTokenResolver bearerOrDpopTokenResolver() {
        DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();
        return request -> {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.regionMatches(true, 0, "DPoP ", 0, 5)) {
                String token = authorization.substring(5).trim();
                return token.isEmpty() ? null : token;
            }
            return bearer.resolve(request);
        };
    }

    static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        OAuth2Error invalidAudience = new OAuth2Error("invalid_token",
                "The token is not issued for " + audience, null);
        return jwt -> {
            List<String> aud = jwt.getAudience();
            boolean allowed = (aud != null && aud.contains(audience)) || audience.equals(jwt.getClaimAsString("azp"));
            return allowed ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(invalidAudience);
        };
    }

    static Converter<Jwt, AbstractAuthenticationToken> keycloakRealmRoles() {
        return jwt -> new JwtAuthenticationToken(jwt, realmRoles(jwt), jwt.getSubject());
    }

    @SuppressWarnings("unchecked")
    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (!(realmAccess instanceof Map<?, ?> access) || !(access.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return ((Collection<Object>) roles).stream()
                .map(String::valueOf)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                .toList();
    }
}
