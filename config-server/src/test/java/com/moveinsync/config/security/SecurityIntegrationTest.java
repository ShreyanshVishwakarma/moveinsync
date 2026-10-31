package com.moveinsync.config.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * HTTP-level proof of the security model: authentication, role checks and tenant
 * isolation on both reads and writes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "moveinsync.seed.enabled=true"
)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SecurityIntegrationTest {

    private static final String ADMIN = "demo-admin-key";
    private static final String OPERATOR = "demo-operator-key";
    private static final String EMPLOYEE = "demo-employee-key";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aRequestWithoutAnApiKeyIsRejected() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/config/version", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("UNAUTHENTICATED");
    }

    @Test
    void anEmployeeCannotReadAnotherTenantOrPublish() {
        ResponseEntity<String> ownOffice = get("/api/v1/config/resolve?key=booking_cutoff_minutes&officeId=blr-office-1", EMPLOYEE);
        assertThat(ownOffice.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(ownOffice).path("value").asText()).isEqualTo("30");

        ResponseEntity<String> otherTenant = get(
                "/api/v1/config/resolve?key=booking_cutoff_minutes&clientId=globex-corp", EMPLOYEE);
        assertThat(otherTenant.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(otherTenant.getBody()).contains("TENANT_FORBIDDEN");

        ResponseEntity<String> snapshot = get("/api/v1/config/snapshot", EMPLOYEE);
        assertThat(snapshot.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snapshot.getBody()).contains("acme-corp");
        assertThat(snapshot.getBody()).doesNotContain("globex-corp");

        ResponseEntity<String> publish = post("/api/v1/change-sets", EMPLOYEE, """
                {"author":"employee","reason":"should not work",
                 "changes":[{"key":"booking_cutoff_minutes","level":"CLIENT","entityId":"acme-corp","value":"10"}]}
                """);
        assertThat(publish.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Audit history spans tenants, so it is admin-only.
        ResponseEntity<String> history = get("/api/v1/change-sets", EMPLOYEE);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anOperatorCanPublishForItsTenantButNotForAnother() {
        ResponseEntity<String> allowed = post("/api/v1/change-sets", OPERATOR, """
                {"author":"operator","reason":"canary",
                 "changes":[{"key":"booking_cutoff_minutes","level":"OFFICE","entityId":"blr-office-1","value":"25"}]}
                """);

        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode entry = json(allowed).path("entries").get(0);
        assertThat(entry.path("clientId").asText()).isEqualTo("acme-corp");
        assertThat(entry.path("newValue").asText()).isEqualTo("25");

        ResponseEntity<String> crossTenant = post("/api/v1/change-sets", OPERATOR, """
                {"author":"operator","reason":"cross tenant",
                 "changes":[{"key":"booking_cutoff_minutes","level":"CLIENT","entityId":"globex-corp","value":"1"}]}
                """);
        assertThat(crossTenant.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> definition = post("/api/v1/definitions", OPERATOR, """
                {"key":"operator_should_not","type":"STRING","defaultValue":"x"}
                """);
        assertThat(definition.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anAdminManagesDefinitionsAndSeesEveryTenant() {
        ResponseEntity<String> definition = post("/api/v1/definitions", ADMIN, """
                {"key":"max_wait_minutes","type":"DURATION","defaultValue":"15","minValue":0,"maxValue":120}
                """);
        assertThat(definition.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> snapshot = get("/api/v1/config/snapshot", ADMIN);
        assertThat(snapshot.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snapshot.getBody()).contains("globex-corp").contains("acme-corp");

        ResponseEntity<String> globex = get(
                "/api/v1/config/resolve?key=booking_cutoff_minutes&clientId=globex-corp", ADMIN);
        assertThat(json(globex).path("value").asText()).isEqualTo("50");
    }

    private ResponseEntity<String> get(String path, String apiKey) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(apiKey)), String.class);
    }

    private ResponseEntity<String> post(String path, String apiKey, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(apiKey)), String.class);
    }

    private HttpHeaders headers(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception ex) {
            throw new AssertionError("Response was not JSON: " + response.getBody(), ex);
        }
    }
}
