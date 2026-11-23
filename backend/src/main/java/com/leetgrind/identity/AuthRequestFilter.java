package com.leetgrind.identity;

import java.io.IOException;
import java.time.Clock;
import java.util.Set;

import com.leetgrind.platform.ApiErrors;
import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.FixedWindowRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

class AuthRequestFilter extends OncePerRequestFilter {
    static final String REGISTER = "/api/v1/auth/register";
    static final String LOGIN = "/api/v1/auth/login";
    static final String REFRESH = "/api/v1/auth/refresh";
    static final String LOGOUT = "/api/v1/auth/logout";
    private static final Set<String> AUTH_PATHS = Set.of(REGISTER, LOGIN, REFRESH, LOGOUT);
    private final AuthProperties properties;
    private final ApiErrors errors;
    private final FixedWindowRateLimiter attempts;

    AuthRequestFilter(AuthProperties properties, ApiErrors errors, Clock clock) {
        this.properties = properties;
        this.errors = errors;
        this.attempts = new FixedWindowRateLimiter(clock, 20);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("POST".equals(request.getMethod()) && AUTH_PATHS.contains(path)) {
            if (!properties.browserOrigin().equals(request.getHeader("Origin"))
                    || !"browser".equals(request.getHeader("X-LeetGrind-Client"))) {
                errors.write(request, response, new Failure(HttpStatus.FORBIDDEN, "invalid_origin",
                        "Authentication requires the LeetGrind web origin."));
                return;
            }
            if ((LOGIN.equals(path) || REGISTER.equals(path)) && !attempts.allow(request.getRemoteAddr())) {
                response.setHeader("Retry-After", "60");
                errors.write(request, response, new Failure(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                        "Too many attempts. Try again in a minute."));
                return;
            }
        }
        chain.doFilter(request, response);
    }

}
