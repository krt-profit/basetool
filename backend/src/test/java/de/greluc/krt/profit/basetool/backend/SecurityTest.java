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
    // First request should pass
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  @Test
  void testAnonymousAccessToMissions() throws Exception {
    mockMvc.perform(get("/api/v1/missions")).andExpect(status().isOk());
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
                        .jwt(jwt)))
        .andExpect(status().isOk());
  }

  /**
   * The material x terminal price matrix is the largest single response the API can produce, and it
   * used to fall into the catalog {@code permitAll} through {@code /api/v1/materials/**} — the
   * cheapest amplification lever an unauthenticated caller had. Its only consumer is an
   * {@code @PreAuthorize("isAuthenticated()")} page controller, so requiring a token costs nothing
   * (A6, REQ-SEC-032). Ordering is what makes this work, and what a future edit could quietly undo:
   * Spring Security takes the first matching rule, so moving this below the catalog block would
   * re-open the surface with no other symptom.
   */
  @Test
  void materialsMatrixIsNotAnonymouslyReachable() throws Exception {
    mockMvc.perform(get("/api/v1/materials/matrix")).andExpect(status().isUnauthorized());
  }

  @Test
  void theRestOfTheMaterialCatalogStaysAnonymous() throws Exception {
    // The carve-out must be surgical: the anonymous order form's material picker still needs the
    // ordinary catalog list, so a too-broad matcher would break the guest flow instead.
    mockMvc.perform(get("/api/v1/materials")).andExpect(status().isOk());
  }

  /**
   * The anonymous page-size ceiling (A6, REQ-SEC-032).
   *
   * <p>{@code PaginationUtil} clamps at 100 000, which is right for the authenticated consumers
   * that page-walk large catalogues and far too generous for a surface anyone can reach. The
   * refusal is deliberate rather than a silent clamp: reducing the size quietly is the defect
   * ADR-0104 forbids, because a caller built on "one big page" would then present an incomplete
   * list as complete.
   */
  @Test
  void anonymousPageSizeAboveTheCeilingIsRefusedRatherThanTruncated() throws Exception {
    mockMvc
        .perform(get("/api/v1/materials").param("size", "50000"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PAGE_SIZE_TOO_LARGE"));
  }

  @Test
  void anonymousPageSizeAtTheCeilingStillWorks() throws Exception {
    // 1000 is what every anonymous caller in the codebase already asks for — the guest order form's
    // pickers and the catalogue page-walks — so the ceiling must not start rejecting them.
    mockMvc.perform(get("/api/v1/materials").param("size", "1000")).andExpect(status().isOk());
  }

  @Test
  void anAuthenticatedCallerIsNotSubjectToTheAnonymousCeiling() throws Exception {
    // The large page-walks are authenticated and must keep their 100 000 clamp.
    mockMvc
        .perform(
            get("/api/v1/materials")
                .param("size", "50000")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());
  }

  @Test
  void testAnonymousAccessToLocations() throws Exception {
    mockMvc.perform(get("/api/v1/locations")).andExpect(status().isOk());
  }

  @Test
  void testAnonymousAccessToJobTypes() throws Exception {
    mockMvc.perform(get("/api/v1/job-types")).andExpect(status().isOk());
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
        .andExpect(status().isOk());
  }

  @Test
  void testAuthenticatedAccessWithNullSub() throws Exception {
    // We create a JWT without a sub claim
    org.springframework.security.oauth2.jwt.Jwt jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("preferred_username", "testuser")
            .build();

    // This should now succeed and not log ERROR (only log WARN)
    mockMvc
        .perform(
            get("/api/v1/missions")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(jwt)))
        .andExpect(status().isOk());
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

    // This should log ERROR but still return 200 for permitAll
    mockMvc
        .perform(
            get("/api/v1/missions")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(jwt)))
        .andExpect(status().isOk());
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
