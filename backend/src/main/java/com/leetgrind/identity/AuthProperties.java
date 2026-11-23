package com.leetgrind.identity;

import java.net.URI;
import java.util.Base64;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("leetgrind.auth")
record AuthProperties(String jwtSecret, String browserOrigin, boolean secureCookie) {
    AuthProperties {
        if (jwtSecret == null || Base64.getDecoder().decode(jwtSecret).length < 32) {
            throw new IllegalArgumentException("LEETGRIND_JWT_SECRET must encode at least 32 random bytes");
        }
        URI origin = URI.create(browserOrigin);
        if (origin.getHost() == null || origin.getUserInfo() != null || !origin.getRawPath().isEmpty()
                || origin.getRawQuery() != null || origin.getRawFragment() != null) {
            throw new IllegalArgumentException("LEETGRIND_BROWSER_ORIGIN must be a single origin without a trailing slash");
        }
        boolean loopback = Set.of("localhost", "127.0.0.1").contains(origin.getHost());
        if (secureCookie ? !"https".equals(origin.getScheme()) : !loopback || !"http".equals(origin.getScheme())) {
            throw new IllegalArgumentException("LeetGrind requires HTTPS or an explicit loopback HTTP development origin");
        }
    }

    String cookieName() {
        return secureCookie ? "__Host-leetgrind_refresh" : "leetgrind_refresh";
    }
}
