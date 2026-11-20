package com.moveinsync.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security.
 *
 * <p>Every endpoint is protected by an explicit role rule; there is no "authenticated
 * enough" catch-all, so adding an endpoint without a rule fails closed (403) rather than
 * silently becoming readable by any valid token.
 *
 * <pre>
 * POST /api/v1/auth/login                     public        (issues a token)
 * GET  /api/v1/auth/me                        any role      (own claims)
 * GET  /api/v1/config/**                      any role      (tenant-scoped reads)
 * POST /api/v1/change-sets                    OPERATOR, ADMIN
 * POST /api/v1/change-sets/{v}/promote        OPERATOR, ADMIN
 * GET  /api/v1/change-sets/**                 ADMIN         (cross-tenant audit)
 * POST /api/v1/rollbacks                      OPERATOR, ADMIN
 * GET  /api/v1/rollbacks/**                   OPERATOR, ADMIN
 * GET  /api/v1/definitions                    any role
 * POST /api/v1/definitions                    ADMIN
 * GET  /actuator/{health,info}                public
 * GET  /actuator/**                           ADMIN
 * </pre>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Login only; tokens are the per-request credential.
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter,
                                                   AuthErrorHandlers authErrorHandlers,
                                                   @Value("${moveinsync.security.h2-console-public:true}") boolean h2ConsolePublic)
            throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> {
                    // --- public -------------------------------------------------------
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll();
                    auth.requestMatchers("/actuator/health", "/actuator/info").permitAll();                    if (h2ConsolePublic) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }

                    // --- authenticated, any role ---------------------------------------
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated();
                    // Live delta channel: authenticated like any read, then tenant-filtered.
                    auth.requestMatchers("/ws/**").hasAnyRole(
                            Role.EMPLOYEE.name(), Role.OPERATOR.name(), Role.ADMIN.name());
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/config/**").hasAnyRole(
                            Role.EMPLOYEE.name(), Role.OPERATOR.name(), Role.ADMIN.name());
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/definitions/**").hasAnyRole(
                            Role.EMPLOYEE.name(), Role.OPERATOR.name(), Role.ADMIN.name());

                    // --- operator and above --------------------------------------------
                    auth.requestMatchers(HttpMethod.POST,
                                    "/api/v1/change-sets",
                                    "/api/v1/change-sets/*/promote")
                            .hasAnyRole(Role.OPERATOR.name(), Role.ADMIN.name());
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/rollbacks")
                            .hasAnyRole(Role.OPERATOR.name(), Role.ADMIN.name());
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/rollbacks/**")
                            .hasAnyRole(Role.OPERATOR.name(), Role.ADMIN.name());

                    // --- admin only ----------------------------------------------------
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/change-sets/**").hasRole(Role.ADMIN.name());
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/definitions").hasRole(Role.ADMIN.name());
                    auth.requestMatchers("/actuator/**").hasRole(Role.ADMIN.name());

                    // Fail closed: anything not listed above requires ADMIN, never "any token".
                    auth.anyRequest().hasRole(Role.ADMIN.name());
                })
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authErrorHandlers)
                        .accessDeniedHandler(authErrorHandlers))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
