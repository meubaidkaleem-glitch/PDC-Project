package org.districompute.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Session-based authentication for the browser application ({@code docs/security.md}): JSON login and logout
 * under {@code /api/v1/auth}, CSRF protection with a cookie token the SPA echoes in {@code X-XSRF-TOKEN}, roles
 * {@code ADMIN} and {@code USER}, and 401 (never a redirect) for unauthenticated API calls.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * The filter chain.
     *
     * @param http security builder
     * @param handlers JSON login handlers
     * @return the chain
     * @throws Exception if misconfigured
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthHandlers handlers) throws Exception {
        http.csrf(csrf -> csrf.spa())
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/session").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/info").permitAll()
                        .requestMatchers("/api/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .authenticated()
                        // Everything else is the single-page application and its static files.
                        .anyRequest().permitAll())
                .formLogin(form -> form.loginProcessingUrl("/api/v1/auth/login")
                        .successHandler(handlers::onSuccess).failureHandler(handlers::onFailure).permitAll())
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> problem(response, HttpStatus.UNAUTHORIZED,
                                "UNAUTHENTICATED", "Sign in to use this endpoint."))
                        .accessDeniedHandler((request, response, ex) -> {
                            if (ex instanceof CsrfException) {
                                problem(response, HttpStatus.FORBIDDEN, "CSRF_TOKEN_INVALID",
                                        "The request lacks a valid CSRF token. Reload the page and try again.");
                            } else {
                                problem(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                        "Your role does not allow this action.");
                            }
                        }))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                + "object-src 'none'; frame-ancestors 'none'; base-uri 'self'")));
        return http.build();
    }

    /**
     * Password hashing.
     *
     * @return BCrypt
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Writes an RFC 9457 problem in the same shape as {@code ApiExceptionHandler}. The texts are fixed, so nothing
     * from the request is echoed.
     */
    static void problem(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"https://districompute.org/problems/" + code.toLowerCase()
                .replace('_', '-') + "\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":"
                + status.value() + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}");
    }

    /** Loads the deferred CSRF token on every request so that its cookie is always set for the SPA. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
