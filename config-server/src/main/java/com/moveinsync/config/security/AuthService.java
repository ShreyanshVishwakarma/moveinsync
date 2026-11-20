package com.moveinsync.config.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

/**
 * Verifies credentials and mints tokens.
 *
 * <p>Role and tenant come from the stored user, never from the login request, so a caller
 * cannot escalate by asking for a different role. Failed logins are deliberately
 * indistinguishable: same message, same status, no hint about which half was wrong.
 */
@Service
public class AuthService {

    private static final String BAD_CREDENTIALS = "Invalid username or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public JwtService.IssuedToken login(String username, String password, Duration requestedTtl) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new InvalidCredentialsException();
        }
        Optional<User> found = userRepository.findByUsernameAndActiveTrue(username.trim());
        if (found.isEmpty()) {
            throw new InvalidCredentialsException();
        }
        User user = found.get();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return jwtService.issue(user.toActor(), user.getUsername(), requestedTtl);
    }

    @Transactional(readOnly = true)
    public Optional<TokenClaims> currentUser(String token) {
        try {
            return Optional.of(jwtService.verify(token));
        } catch (InvalidTokenException ex) {
            return Optional.empty();
        }
    }

    public static String badCredentialsMessage() {
        return BAD_CREDENTIALS;
    }

    /** Thrown for any failed login; intentionally carries no detail. */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super(BAD_CREDENTIALS);
        }
    }
}
