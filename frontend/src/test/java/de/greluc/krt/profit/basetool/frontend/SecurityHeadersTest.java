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

package de.greluc.krt.profit.basetool.frontend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest
class SecurityHeadersTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private WebClient webClient;

  @MockitoBean(name = "termsDocumentClient")
  private WebClient termsDocumentClient;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void shouldExposeSecurityHeadersOnHome() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(header().exists("Content-Security-Policy"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
        .andExpect(header().exists("Permissions-Policy"));
  }

  /**
   * Audit finding L-3 (2026-05-20): the CSP {@code style-src} directive must be nonce-gated and
   * must NOT carry {@code 'unsafe-inline'} — every {@code <style>} block in the templates now
   * renders with {@code th:attr="nonce=${cspNonce}"}, so an injected {@code <style>} tag (stored
   * XSS via mission name / finance note / …) cannot be evaluated by the browser. Inline {@code
   * style=""} attributes are now locked out too: {@code style-src-attr} is {@code 'none'} because
   * every inline style attribute was migrated out of the templates (static values to {@code
   * inline-migration.css} classes; data-driven ones to {@code th:classappend} classes or a {@code
   * data-krtm-width} attribute applied via the CSSOM), so an injected inline style attribute is
   * blocked rather than merely restricted to visual harm.
   */
  @Test
  void cspStyleSrcIsNonceGated_andStyleSrcAttrIsNone() throws Exception {
    String csp =
        mockMvc
            .perform(get("/"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("Content-Security-Policy");

    assertThat(csp).as("Content-Security-Policy header").isNotNull();
    // Nonce embedded in style-src — the placeholder is replaced per request, so we only assert
    // the prefix + the nonce-pattern.
    assertThat(csp)
        .as("style-src must be nonce-gated, not 'unsafe-inline'")
        .containsPattern("style-src 'self' 'nonce-[A-Za-z0-9_-]+'");
    assertThat(csp)
        .as("style-src must NOT carry 'unsafe-inline' (would defeat the nonce gate)")
        .doesNotContain("style-src 'self' 'unsafe-inline'");
    // style-src-attr is now 'none': all inline style="" attributes were migrated out of the
    // templates (static -> inline-migration.css classes; dynamic -> th:classappend classes /
    // data-krtm-width applied via the CSSOM), so an injected inline style attribute is blocked.
    assertThat(csp)
        .as("style-src-attr locked to 'none' — no inline style attributes remain")
        .contains("style-src-attr 'none'")
        .doesNotContain("style-src-attr 'unsafe-inline'");
    // script-src stays nonce-gated as before — regression-pin.
    assertThat(csp).contains("script-src 'nonce-").contains("'strict-dynamic'");
  }

  /**
   * Regression-pin for the POST-logout CSP block. Logout became a CSRF-safe POST form (audit
   * finding L-3); its success redirect targets Keycloak's cross-origin {@code
   * end_session_endpoint}. With {@code form-action 'self'} alone, Chromium blocks that cross-origin
   * redirect, so the local Spring session is cleared but the Keycloak SSO session survives and the
   * next login silently re-authenticates instead of prompting for credentials. The Keycloak origin
   * (derived from the configured {@code issuer-uri}) must therefore appear in {@code form-action}.
   * Under the {@code test} profile the issuer is {@code http://keycloak.example.com/realms/test},
   * so the expected origin is {@code http://keycloak.example.com}.
   */
  @Test
  void cspFormActionAllowsKeycloakOriginForLogoutRedirect() throws Exception {
    String csp =
        mockMvc
            .perform(get("/"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("Content-Security-Policy");

    assertThat(csp).as("Content-Security-Policy header").isNotNull();
    assertThat(csp)
        .as(
            "form-action must allow 'self' plus the Keycloak origin so the POST-logout redirect to"
                + " Keycloak's end_session_endpoint is not blocked by the browser")
        .contains("form-action 'self' http://keycloak.example.com");
  }
}
