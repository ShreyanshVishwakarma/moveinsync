package com.moveinsync.config.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.dto.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level proof of the JWT model: login, role-based authorisation on every endpoint,
 * tenant isolation, the 7-day ceiling and immediate revocation.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "moveinsync.seed.enabled=true"
)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class JwtSecurityIntegrationTest {

    private static final String PASSWORD = "password123";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void loginIssuesABearerTokenWithRoleAndTenant() throws Exception {
        ResponseEntity<String> response = login("operator", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        assertThat(body.path("accessToken").asText()).isNotBlank();
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("role").asText()).isEqualTo("OPERATOR");
        assertThat(body.path("clientId").asText()).isEqualTo("acme-corp");
        // Default policy is the 7-day maximum.
        assertThat(body.path("expiresInSeconds").asLong()).isEqualTo(7L * 24 * 3600);
    }

    @Test
    void wrongCredentialsFailWithoutLeakingWhichHalfWasWrong() throws Exception {
        ResponseEntity<String> wrongPassword = login("operator", "nope");
        ResponseEntity<String> unknownUser = login("nobody", "nope");

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(wrongPassword).path("message").asText())
                .isEqualTo(json(unknownUser).path("message").asText());
    }

    @Test
    void readsRequireATokenAndAreTenantScoped() throws Exception {
        ResponseEntity<String> anonymous = rest.getForEntity("/api/v1/config/version", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String employee = token("employee");
        ResponseEntity<String> ownOffice = get(
                "/api/v1/config/resolve?key=booking_cutoff_minutes&officeId=blr-office-1", employee);
        assertThat(ownOffice.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(ownOffice).path("value").asText()).isEqualTo("30");

        ResponseEntity<String> crossTenant = get(
                "/api/v1/config/resolve?key=booking_cutoff_minutes&clientId=globex-corp", employee);
        assertThat(crossTenant.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(crossTenant.getBody()).contains("TENANT_FORBIDDEN");
    }

    @Test
    void everyWriteEndpointIsRoleProtected() throws Exception {
        String employee = token("employee");
        String operator = token("operator");
        String admin = token("admin");

        String changeSet = """
                {"author":"t","reason":"t","changes":[
                  {"key":"booking_cutoff_minutes","level":"OFFICE","entityId":"blr-office-1","value":"25"}]}
                """;

        assertThat(post("/api/v1/change-sets", employee, changeSet).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/change-sets", operator, changeSet).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        String definition = """
                {"key":"jwt_test_setting","type":"DURATION","defaultValue":"15","minValue":0,"maxValue":60}
                """;
        assertThat(post("/api/v1/definitions", operator, definition).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/definitions", admin, definition).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(get("/api/v1/change-sets", operator).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/change-sets", admin).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(get("/actuator/metrics", employee).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/actuator/metrics", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void meEchoesClaimsAndRequiresABearerToken() throws Exception {
        String operator = token("operator");

        ResponseEntity<String> response = get("/api/v1/auth/me", operator);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        assertThat(body.path("username").asText()).isEqualTo("operator");
        assertThat(body.path("role").asText()).isEqualTo("OPERATOR");
        assertThat(body.path("secondsRemaining").asLong()).isGreaterThan(0);
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        String operator = token("operator");
        String tampered = operator.substring(0, operator.length() - 2) + "AA";

        ResponseEntity<String> response = get("/api/v1/config/version", tampered);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(response).path("code").asText()).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void logoutRevokesTheTokenImmediately() throws Exception {
        String operator = token("operator");
        assertThat(get("/api/v1/config/version", operator).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> logout = post("/api/v1/auth/logout", operator, null);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> after = get("/api/v1/config/version", operator);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(after).path("code").asText()).isEqualTo("TOKEN_REVOKED");
    }

    @Test
    void aRequestedLifetimeIsCappedAtSevenDays() throws Exception {
        ResponseEntity<String> response = login("admin", PASSWORD, 365L * 24 * 3600);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).path("expiresInSeconds").asLong()).isEqualTo(7L * 24 * 3600);
    }

    // ------------------------------------------------------------------ helpers

    private String token(String username) throws Exception {
        ResponseEntity<String> response = login(username, PASSWORD);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(response).path("accessToken").asText();
    }

    private ResponseEntity<String> login(String username, String password) {
        return login(username, password, null);
    }

    private ResponseEntity<String> login(String username, String password, Long ttlSeconds) {
        LoginRequest request = new LoginRequest(username, password, ttlSeconds);
        String body;
        try {
            body = objectMapper.writeValueAsString(request);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        // HttpURLConnection cannot read a streaming 401 body, which is exactly what a failed
        // login returns, so POST with java.net.http instead.
        try {
            java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(rest.getRootUri() + "/api/v1/auth/login"))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();

            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString());

            return ResponseEntity.status(response.statusCode()).body(response.body());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("login request failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("login request interrupted", ex);
        }
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(token)), String.class);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }
}
