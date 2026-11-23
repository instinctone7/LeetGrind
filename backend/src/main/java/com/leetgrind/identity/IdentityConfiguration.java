package com.leetgrind.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
class IdentityConfiguration {
    static final String ISSUER = "https://leetgrind.local/identity";
    static final String AUDIENCE = "leetgrind-api";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecretKey jwtKey(AuthProperties properties) {
        return new SecretKeySpec(Base64.getDecoder().decode(properties.jwtSecret()), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, SessionFamilyRepository families, Clock clock) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> session = jwt -> {
            String sessionId = jwt.getClaimAsString("sid");
            if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())
                    || jwt.getAudience() == null || !jwt.getAudience().contains(AUDIENCE)
                    || sessionId == null || sessionId.length() != 36) {
                return invalidSession();
            }
            try {
                long accountId = Long.parseLong(jwt.getSubject());
                return families.isActive(sessionId, accountId, clock.instant())
                        ? OAuth2TokenValidatorResult.success() : invalidSession();
            } catch (NumberFormatException invalidSubject) {
                return invalidSession();
            }
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(ISSUER), timestamps, session));
        return decoder;
    }

    private static OAuth2TokenValidatorResult invalidSession() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }
}
