package com.moveinsync.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless security.
 *
 * <p>Every API request must carry an API key. Reads are open to any authenticated role
 * (scoped to the caller's tenant), publications require OPERATOR or ADMIN, and schema
 * changes require ADMIN. The H2 console is public only in the demo profile.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ApiKeyAuthenticationFilter apiKeyFilter,
                                                   AuthErrorHandlers authErrorHandlers,
                                                   @Value("${moveinsync.security.h2-console-public:true}") boolean h2ConsolePublic)
            throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/actuator/info").permitAll();

                    if (h2ConsolePublic) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }

                    auth.requestMatchers("/actuator/**").hasRole(Role.ADMIN.name());
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/definitions").hasRole(Role.ADMIN.name());
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/change-sets", "/api/v1/change-sets/*/promote")
                            .hasAnyRole(Role.OPERATOR.name(), Role.ADMIN.name());
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/rollbacks")
                            .hasAnyRole(Role.OPERATOR.name(), Role.ADMIN.name());
                    // History exposes every tenant's audit rows; only admins may read it.
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/change-sets/**").hasRole(Role.ADMIN.name());

                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authErrorHandlers)
                        .accessDeniedHandler(authErrorHandlers))
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
