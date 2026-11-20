package com.moveinsync.config.security;

import com.moveinsync.config.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates every request from a bearer token.
 *
 * <p>Accepts {@code Authorization: Bearer <jwt>} (the standard) and still honours
 * {@code X-API-Key} for service-to-service callers that cannot log in. A rejected token
 * short-circuits with a JSON 401 so clients never see a redirect or a stack trace.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ApiKeyService apiKeyService;
    private final TokenRevocationService revocationService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   ApiKeyService apiKeyService,
                                   TokenRevocationService revocationService,
                                   com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.apiKeyService = apiKeyService;
        this.revocationService = revocationService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String bearer = bearerToken(request);
        if (bearer != null) {
            try {
                TokenClaims claims = jwtService.verify(bearer);
                if (revocationService.isRevoked(claims.tokenId())) {
                    writeUnauthorized(response, request, "TOKEN_REVOKED", "Access token has been revoked");
                    return;
                }
                authenticate(claims.toActor(), request);
            } catch (InvalidTokenException ex) {
                writeUnauthorized(response, request, ex.getCode(), ex.getMessage());
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        // Service accounts keep using API keys; they map to the same principal shape.
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            apiKeyService.authenticate(apiKey).ifPresent(actor -> authenticate(actor, request));
        }

        filterChain.doFilter(request, response);
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            // WebSocket upgrades and browser tools cannot always set headers.
            String queryToken = request.getParameter("token");
            return queryToken == null || queryToken.isBlank() ? null : queryToken;
        }
        if (!header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void authenticate(Actor actor, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(actor, null, actor.authorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void writeUnauthorized(HttpServletResponse response,
                                   HttpServletRequest request,
                                   String code,
                                   String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.of(HttpStatus.UNAUTHORIZED.value(), code, message, request.getRequestURI());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
