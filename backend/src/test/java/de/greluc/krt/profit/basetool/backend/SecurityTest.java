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
        .perform(
            get("/v3/api-docs").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().exists("Content-Security-Policy"));
  }

  /**
   * Verifies that the JSON-only backend sends a locked-down Content-Security-Policy ({@code
   * default-src 'none'}) without {@code 'unsafe-inline'} or {@code data:} relaxations.
   *
   * @throws Exception if the MockMvc request fails
   */
  @Test
  void contentSecurityPolicyIsLockedDownForJsonOnlyBackend() throws Exception {
    mockMvc
        .perform(
            get("/v3/api-docs").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
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
   * Verifies that the material x terminal price matrix, the API's largest response, is not
   * reachable anonymously (REQ-SEC-032).
   */
  @Test
  void materialsMatrixIsNotAnonymouslyReachable() throws Exception {
    mockMvc.perform(get("/api/v1/materials/matrix")).andExpect(status().isUnauthorized());
  }

  /** Verifies that the per-material terminal prices are not reachable anonymously. */
  @Test
  void materialTerminalPricesAreNotAnonymouslyReachable() throws Exception {
    mockMvc
        .perform(get("/api/v1/materials/00000000-0000-4000-8000-00000000cafe/terminals"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * Verifies that the material matrix and per-material prices are not reachable anonymously with
   * {@code HEAD}, which Spring MVC answers from the {@code GET} handler.
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

  /** Verifies that the rest of the material catalogue is not anonymous either (REQ-SEC-052). */
  @Test
  void theRestOfTheMaterialCatalogIsNotAnonymousEither() throws Exception {
    mockMvc.perform(get("/api/v1/materials")).andExpect(status().isUnauthorized());
  }

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
        .andExpect(status().isForbidden());
  }

  @Test
  void testAuthenticatedAccessWithNullSub() throws Exception {
    org.springframework.security.oauth2.jwt.Jwt jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("preferred_username", "testuser")
            .build();

    mockMvc
        .perform(
            get("/api/v1/missions")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(jwt)))
        .andExpect(status().isForbidden());
  }

  @Test
  void testAuthenticatedAccessWithBothNullSubAndUsername() throws Exception {
    org.springframework.security.oauth2.jwt.Jwt jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("foo", "bar")
            .build();

    mockMvc
        .perform(
            get("/api/v1/missions")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(jwt)))
        .andExpect(status().isForbidden());
  }

  /**
   * Verifies that the Terms-of-Use wording is readable without a token, as the public {@code
   * /terms} page and the Android app require (ADR-0138, REQ-SEC-028).
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
   * Verifies that the consent status stays behind authentication even though the wording is public.
   */
  @Test
  void termsStatusStaysAuthenticated() throws Exception {
    mockMvc.perform(get("/api/v1/terms/status")).andExpect(status().isUnauthorized());
  }
}
