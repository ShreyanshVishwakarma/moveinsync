package com.moveinsync.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Turns Spring Security's 401/403 into the same JSON envelope the rest of the API uses,
 * so clients never have to special-case auth failures.
 */
@Component
public class AuthErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public AuthErrorHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, request, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "A valid API key is required");
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, request, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "This API key is not allowed to perform that action");
    }

    private void write(HttpServletResponse response,
                       HttpServletRequest request,
                       HttpStatus status,
                       String code,
                       String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.of(status.value(), code, message, request.getRequestURI());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
