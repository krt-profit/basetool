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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Verifies that {@link SecurityConfig} permits anonymous access to static-asset paths so that
 * background sourcemap and asset probes from DevTools and browser extensions never trigger the
 * OAuth2 entry point. The crucial assertion is that none of these paths returns a 3xx redirect —
 * the actual resource may resolve (200) or be missing (404), but it must never land in {@link
 * SsoReAuthenticationEntryPoint} and therefore never be stored as a saved request.
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityConfigStaticAssetPermitAllTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private WebClient webClient;

  @MockitoBean(name = "termsDocumentClient")
  private WebClient termsDocumentClient;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * The asset paths exercised here cover the reasons such a request can show up in the wild:
   *
   * <ul>
   *   <li>{@code /js/vendor/example-1.0.0.min.js.map} — DevTools sourcemap lookup for a {@code
   *       /js/vendor/} bundle path that 404s (vendored bundles ship without sourcemaps).
   *   <li>{@code /sm/abcdef123456.map} — Sentry-Replay / browser-extension sourcemap probe with
   *       arbitrary hash.
   *   <li>{@code /favicon.ico} — no favicon shipped in {@code static/}.
   *   <li>{@code /robots.txt} and {@code /css/styles.css} — present in {@code static/}.
   *   <li>{@code /css/does-not-exist.css.map} — generic {@code /**}/{@code *.map} probe against a
   *       CSS sourcemap.
   *   <li>{@code /error/foo} — non-default Spring Boot error dispatch subpath; must stay open so it
   *       never lands in the saved-request slot.
   * </ul>
   *
   * <p>Exact response status is intentionally not asserted — known files resolve 200, missing files
   * resolve 404, and {@code /error}-style paths can resolve 500 because no error attribute is set
   * on a direct GET. The discriminating check is that {@link
   * MockHttpServletResponse#getRedirectedUrl()} stays {@code null}: a hit on {@link
   * SsoReAuthenticationEntryPoint} would set the Location header to {@code
   * /oauth2/authorization/keycloak}.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/js/vendor/example-1.0.0.min.js.map",
        "/sm/abcdef123456.map",
        "/favicon.ico",
        "/robots.txt",
        "/css/styles.css",
        "/css/does-not-exist.css.map",
        "/error/foo"
      })
  @WithAnonymousUser
  void anonymousGetOnStaticAssetPath_doesNotRedirectToOAuth2Login(String path) throws Exception {
    // When
    MockHttpServletResponse response = mockMvc.perform(get(path)).andReturn().getResponse();

    // Then — SsoReAuthenticationEntryPoint emits a 302 to /oauth2/authorization/keycloak when it
    // gates a request. The resource handler and the static-content path emit no redirect. Asserting
    // a null redirect URL catches the only failure mode that matters for this fix.
    assertNull(
        response.getRedirectedUrl(),
        "Path "
            + path
            + " unexpectedly redirected to "
            + response.getRedirectedUrl()
            + " — Spring Security should not gate static-asset paths.");
  }
}
