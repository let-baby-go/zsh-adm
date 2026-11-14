package com.techflag.workdsh.admin;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The console cookie works only on console routes, only same-site, and writes only from the console origin. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:browser-session-test?mode=memory&cache=shared",
        "workdsh.bootstrap.admin-email=browser-session@example.test",
        "workdsh.bootstrap.admin-password=BrowserSessionPassword123!",
        "workdsh.web.origin=https://console.example.test",
        "workdsh.secrets.service-key=browser-session-service-key-at-least-32-characters",
        "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("enterprise-sqlite")
@AutoConfigureMockMvc
class BrowserSessionIsolationTest {
    private static final String ORIGIN = "https://console.example.test";
    private static final String SERVICE_KEY = "browser-session-service-key-at-least-32-characters";

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    private Cookie session;

    @BeforeEach
    void browserLogin() throws Exception {
        var login = mvc.perform(post("/api/auth/browser-login").header("Origin", ORIGIN).header("Sec-Fetch-Site", "same-origin")
                        .contentType("application/json").content("{\"email\":\"browser-session@example.test\",\"password\":\"BrowserSessionPassword123!\"}"))
                .andExpect(status().isOk()).andReturn();
        String header = login.getResponse().getHeader("Set-Cookie");
        assertTrue(header.contains("HttpOnly") && header.contains("SameSite=Strict"));
        session = new Cookie(BrowserSessionFilter.COOKIE_NAME, header.substring(header.indexOf('=') + 1, header.indexOf(';')));
    }

    @Test
    void cookieAuthenticatesConsoleRoutesOnly() throws Exception {
        mvc.perform(get("/api/admin/members/summary").cookie(session).header("Sec-Fetch-Site", "same-origin")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isOk());
        // Member, collaboration, Desktop and internal routes never turn the cookie into a member identity.
        mvc.perform(get("/api/collaboration/inbox").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/members").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/member-secrets/records").cookie(session).header(ServiceKeyGuard.HEADER, SERVICE_KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/member/visible-sessions").param("deviceId", "d").param("sessionId", "s").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void crossSiteAndForeignOriginWritesAreRejected() throws Exception {
        mvc.perform(get("/api/admin/members/summary").cookie(session).header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/organization/policy").cookie(session).contentType("application/json")
                .content("{\"directoryScope\":\"ORGANIZATION\",\"sharingEnabled\":true,\"revision\":0,\"bodyRetentionDays\":0}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/organization/policy").cookie(session).header("Origin", "https://evil.example.test").contentType("application/json")
                .content("{\"directoryScope\":\"ORGANIZATION\",\"sharingEnabled\":true,\"revision\":0,\"bodyRetentionDays\":0}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/organization/policy").cookie(session).header("Origin", ORIGIN).contentType("application/json")
                .content("{\"directoryScope\":\"ORGANIZATION\",\"sharingEnabled\":true,\"revision\":0,\"bodyRetentionDays\":0}")).andExpect(status().isOk());
    }

    @Test
    void explicitAuthorizationWinsOverTheCookie() throws Exception {
        mvc.perform(get("/api/auth/me").cookie(session).header("Authorization", "Bearer not-a-valid-token")).andExpect(status().isUnauthorized());
        String bearer = "Bearer " + auth.login("browser-session@example.test", "BrowserSessionPassword123!").token();
        mvc.perform(get("/api/collaboration/inbox").header("Authorization", bearer)).andExpect(status().isOk());
    }

    @Test
    void internalRoutesReportServiceKeyFailures() throws Exception {
        String bearer = "Bearer " + auth.login("browser-session@example.test", "BrowserSessionPassword123!").token();
        mvc.perform(get("/api/internal/member-secrets/records").header("Authorization", bearer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Service key required"));
        mvc.perform(get("/api/internal/member-secrets/records").header("Authorization", bearer).header(ServiceKeyGuard.HEADER, "wrong-wrong-wrong-wrong-wrong-wrong-wrong"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Invalid service key"));
        mvc.perform(get("/api/internal/member-secrets/records").header("Authorization", bearer).header(ServiceKeyGuard.HEADER, SERVICE_KEY))
                .andExpect(status().isOk());
        mvc.perform(get("/api/internal/transport-record").header(ServiceKeyGuard.HEADER, "wrong-wrong-wrong-wrong-wrong-wrong-wrong"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Invalid service key"));
    }

    @Test
    void unconfiguredServiceKeyIsAServerError() {
        var guard = new ServiceKeyGuard("too-short");
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> guard.require("too-short")).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> new ServiceKeyGuard("").require(null)).getStatusCode().value());
    }
}
