package com.leetgrind.identity;

import java.time.Clock;

import com.leetgrind.platform.ApiErrors;
import com.leetgrind.platform.ApiErrors.Failure;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class WebSecurity {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthProperties properties, ApiErrors errors, Clock clock)
            throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> errors.write(request, response,
                new Failure(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue."));
        http
                // Cookie-based authentication endpoints require an exact origin plus a non-simple request header.
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers(HttpMethod.POST, AuthRequestFilter.REGISTER, AuthRequestFilter.LOGIN,
                                AuthRequestFilter.REFRESH, AuthRequestFilter.LOGOUT).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**")
                        .access((authentication, context) ->
                                new AuthorizationDecision("127.0.0.1".equals(context.getRequest().getRemoteAddr())))
                        .requestMatchers("/actuator/health/readiness", "/", "/index.html", "/assets/**", "/openapi.yaml",
                                "/error").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((request, response, exception) -> errors.write(request, response,
                                new Failure(HttpStatus.FORBIDDEN, "forbidden", "Access is denied."))))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}).authenticationEntryPoint(unauthorized))
                .addFilterBefore(new AuthRequestFilter(properties, errors, clock), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
