package com.moveinsync.config.controller;

import com.moveinsync.config.dto.LoginRequest;
import com.moveinsync.config.dto.LoginResponse;
import com.moveinsync.config.dto.MeResponse;
import com.moveinsync.config.security.Actor;
import com.moveinsync.config.security.AuthService;
import com.moveinsync.config.security.InvalidTokenException;
import com.moveinsync.config.security.JwtService;
import com.moveinsync.config.security.TokenClaims;
import com.moveinsync.config.security.TokenRevocationService;
import com.moveinsync.config.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * Token issuance and introspection. These are the only endpoints reachable without a token.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final TokenRevocationService revocationService;

    public AuthController(AuthService authService,
                          JwtService jwtService,
                          TokenRevocationService revocationService) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.revocationService = revocationService;
    }

    /**
     * Exchanges credentials for a bearer token. {@code ttlSeconds} is optional and is
     * clamped to the server default and the 7-day policy ceiling.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        Duration ttl = request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds());
        JwtService.IssuedToken issued = authService.login(request.username(), request.password(), ttl);
        return ResponseEntity.ok(new LoginResponse(
                issued.token(),
                "Bearer",
                issued.ttl().toSeconds(),
                issued.expiresAt().toString(),
                issued.tokenId(),
                issued.subject(),
                issued.actor().role(),
                issued.actor().clientId()
        ));
    }

    /** Echoes the caller's own claims; useful for the UI to render the session. */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Actor actor, HttpServletRequest request) {
        String bearer = bearer(request);
        if (bearer == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_MISSING",
                    "This endpoint requires a bearer token, not an API key");
        }
        TokenClaims claims;
        try {
            claims = jwtService.verify(bearer);
        } catch (InvalidTokenException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ex.getCode(), ex.getMessage());
        }

        Instant now = Instant.now();
        return new MeResponse(
                claims.subject(),
                claims.role(),
                claims.clientId(),
                claims.tokenId(),
                claims.issuedAt().toString(),
                claims.expiresAt().toString(),
                Math.max(0, Duration.between(now, claims.expiresAt()).toSeconds())
        );
    }

    /** Revokes the caller's own token, so "log out" takes effect immediately. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Actor actor, HttpServletRequest request) {
        String bearer = bearer(request);
        if (bearer == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_MISSING",
                    "Logout requires a bearer token; API keys are revoked by an admin");
        }
        try {
            TokenClaims claims = jwtService.verify(bearer);
            revocationService.revoke(claims, "logout");
        } catch (InvalidTokenException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ex.getCode(), ex.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    private String bearer(HttpServletRequest request) {        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
