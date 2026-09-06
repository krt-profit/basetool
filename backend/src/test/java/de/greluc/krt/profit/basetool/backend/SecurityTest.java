/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.filter.RateLimitingFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class SecurityTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private RateLimitingFilter rateLimitingFilter;

  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void testCorsHeaders() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/missions")
                .header("Origin", "http://localhost:8080")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().exists("Access-Control-Allow-Origin"));
  }

  @Test
  void testCorsHeaders_ForbiddenOrigin() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/missions")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }

  @Test
  void testSecurityHeaders() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().exists("Content-Security-Policy"));
  }

  /**
   * Pins the hardened Content-Security-Policy for the JSON-only backend. The backend serves no HTML
   * (Swagger UI was removed), so the policy locks down to {@code default-src 'none'}. This test
   * fails loudly if a future change re-introduces the Swagger-era relaxations ({@code
   * 'unsafe-inline'} on {@code style-src}, {@code data:} img/font sources) or otherwise loosens the
   * lockdown — those would silently re-open a (would-be) XSS surface.
   *
   * @throws Exception if the MockMvc request fails
   */
  @Test
  void contentSecurityPolicyIsLockedDownForJsonOnlyBackend() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Security-Policy",
                    "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action"
                        + " 'none'"));
  }

  @Test
  void testRateLimiting() throws Exception {
    // First request should pass. Admin-gated since REQ-SEC-052: the document enumerates every
    // path, parameter and DTO field the API has, which is the most efficient description of the
    // attack surface the project can produce.
    mockMvc
        .perform(
            get("/v3/api-docs")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  /** The OpenAPI document is not part of the public surface (REQ-SEC-052). */
  @Test
  void openApiDocumentIsNotAnonymouslyReachable() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
  }

  @Test
  void testAnonymousAccessToMissions() throws Exception {
    mockMvc.perform(get("/api/v1/missions")).andExpect(status().isUnauthorized());
  }

  @Test
  void testAuthenticatedAccessToMissions() throws Exception {
    org.springframework.security.oauth2.jwt.Jwt jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", java.util.UUID.randomUUID().toString())
            .claim("preferred_username", "testuser")
            .build();

    mockMvc
        .perform(
            get("/api/v1/missions")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(jwt)
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());
  }

  /**
   * The material x terminal price matrix is the largest single response the API can produce, and it
   * used to fall into the catalog {@code permitAll} through {@code /api/v1/materials/**} — the
   * cheapest amplification lever an unauthenticated caller had. Its only consumer is an
   * {@code @PreAuthorize("isAuthenticated()")} page controller, so requiring a token costs nothing
   * (A6, REQ-SEC-032).
   *
   * <p>Kept after REQ-SEC-052 closed the whole catalogue, which makes the explicit carve-out
   * redundant — and that is exactly why the assertion stays: the rule it pinned is gone from {@code
   * SecurityConfig}, so nothing but this test now says the path must not answer anonymously.
   */
  @Test
  void materialsMatrixIsNotAnonymouslyReachable() throws Exception {
    mockMvc.perform(get("/api/v1/materials/matrix")).andExpect(status().isUnauthorized());
  }

  /**
   * The per-material slice of that same matrix, which the carve-out above was missing.
   *
   * <p>Its only consumer is the inventory page's "where can I sell this" suggestion, which is
   * authenticated — so the reasoning of {@code /materials/matrix} applies unchanged, and leaving it
   * anonymous published UEX trade prices per material to the internet from the API vhost. The
   * nightly {@code edge-deny-probe} asserted {@code 401} for it from the day the phase-3 paste
   * landed and got {@code 200}; the expectation was right and the rule was simply absent.
   */
  @Test
  void materialTerminalPricesAreNotAnonymouslyReachable() throws Exception {
    mockMvc
        .perform(get("/api/v1/materials/00000000-0000-4000-8000-00000000cafe/terminals"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The same two paths, asked for with {@code HEAD}.
   *
   * <p>The carve-out that used to sit above was registered with {@code HttpMethod.GET}, and Spring
   * Security compares the verb with {@code String.equals} - so a {@code HEAD} missed it and fell
   * through to the all-verb catalogue {@code permitAll} underneath. Spring MVC then answers {@code
   * HEAD} from the {@code @GetMapping} handler, so the query ran anonymously and the {@code
   * Content-Length} came back. The lesson outlived the rule: the two remaining anonymous reads are
   * {@code GET}-scoped on purpose, and {@code AnonymousSurfaceSweepTest} asks {@code HEAD} of every
   * {@code GET} mapping for exactly this reason.
   */
  @Test
  void materialsMatrixIsNotAnonymouslyReachableWithHead() throws Exception {
    mockMvc.perform(head("/api/v1/materials/matrix")).andExpect(status().isUnauthorized());
  }

  @Test
  void materialTerminalPricesAreNotAnonymouslyReachableWithHead() throws Exception {
    mockMvc
        .perform(head("/api/v1/materials/00000000-0000-4000-8000-00000000cafe/terminals"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The rest of the material catalogue is not anonymous either (REQ-SEC-052).
   *
   * <p>This test used to assert the opposite, and its comment gave the reason: "the anonymous order
   * form's material picker still needs the ordinary catalog list". That form went with ADR-0149 and
   * the picker now rides a member's bearer, so the carve-out no longer has to be surgical — the
   * whole family requires a login.
   */
  @Test
  void theRestOfTheMaterialCatalogIsNotAnonymousEither() throws Exception {
    mockMvc.perform(get("/api/v1/materials")).andExpect(status().isUnauthorized());
  }

  // The anonymous page-size ceiling (A6, REQ-SEC-032) and its six cases stood here. The filter
  // and the ceiling are gone with ADR-0159: it bounded what an unauthenticated caller could ask a
  // paginated endpoint for, and no paginated endpoint answers one any more. The two reads that do
  // are unpaginated. The per-subject limiter that used to be anchored behind the filter now hangs
  // off TermsAcceptanceAccessFilter, pinned by SecurityFilterChainOrderTest.

  @Test
  void testAnonymousAccessToLocations() throws Exception {
    mockMvc.perform(get("/api/v1/locations")).andExpect(status().isUnauthorized());
  }

  @Test
  void testAnonymousAccessToJobTypes() throws Exception {
    mockMvc.perform(get("/api/v1/job-types")).andExpect(status().isUnauthorized());
  }

  @Test
  void testAuthenticatedAccessWithInvalidSub() throws Exception {
    org.springframework.security.oauth2.jwt.Jwt jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", "not-a-uuid")
            .claim("preferred_username", "testuser")
            .build();

    mockMvc
        .perform(
            get("/api/v1/missions")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(jwt)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NO_ROLE"));
  }

  @Test
  void testAuthenticatedAccessWithNullSub() throws Exception {
    // We create a JWT without a sub claim
    org.springframework.security.oauth2.jwt.Jwt jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("preferred_username", "testuser")
            .build();

    // Reaches a handler no longer: a token that resolves to no application role is refused with
    // 403 NO_ROLE (REQ-SEC-053) before dispatch. It used to answer 200 because /api/v1/missions was
    // permitAll and the odd sub only produced a WARN.
    mockMvc
        .perform(
            get("/api/v1/missions")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(jwt)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NO_ROLE"));
  }

  @Test
  void testAuthenticatedAccessWithBothNullSubAndUsername() throws Exception {
    // We create a JWT without sub and without preferred_username, but with some other claim to
    // satisfy Jwt.Builder
    org.springframework.security.oauth2.jwt.Jwt jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("foo", "bar")
            .build();

    // Logs ERROR as before, and is then refused with 403 NO_ROLE (REQ-SEC-053) — it used to
    // answer 200 because /api/v1/missions was permitAll.
    mockMvc
        .perform(
            get("/api/v1/missions")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(jwt)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NO_ROLE"));
  }

  /**
   * The Terms-of-Use wording is readable without a token (ADR-0138, REQ-SEC-028).
   *
   * <p>Pinned here rather than left to the SecurityConfig entry, because the whole design depends
   * on it: the public {@code /terms} page fetches this anonymously, and the Android app has to be
   * able to show the wording before the member has agreed to anything. Should the rule be reordered
   * behind the authenticated catch-all, the page and the app both go blank while every other test
   * stays green.
   */
  @Test
  void termsDocumentIsReadableAnonymously() throws Exception {
    mockMvc
        .perform(get("/api/v1/terms/document"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").exists())
        .andExpect(jsonPath("$.version").isNotEmpty())
        .andExpect(jsonPath("$.sections").isArray());
  }

  /**
   * Consent itself stays behind authentication.
   *
   * <p>The counterpart to the test above, and the reason the two live in separate controllers:
   * opening the wording must not open the record of who agreed to it. A permitAll that had been
   * written one path segment too short -- {@code /api/v1/terms/**} -- would pass the test above and
   * fail this one.
   */
  @Test
  void termsStatusStaysAuthenticated() throws Exception {
    mockMvc.perform(get("/api/v1/terms/status")).andExpect(status().isUnauthorized());
  }
}
