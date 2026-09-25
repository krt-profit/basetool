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
@org.springframework.security.test.context.support.WithMockUser
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
   * Verifies that the CSP {@code style-src} directive is nonce-gated without {@code
   * 'unsafe-inline'} and that {@code style-src-attr} is {@code 'none'}.
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
    assertThat(csp)
        .as("style-src must be nonce-gated, not 'unsafe-inline'")
        .containsPattern("style-src 'self' 'nonce-[A-Za-z0-9_-]+'");
    assertThat(csp)
        .as("style-src must NOT carry 'unsafe-inline' (would defeat the nonce gate)")
        .doesNotContain("style-src 'self' 'unsafe-inline'");
    assertThat(csp)
        .as("style-src-attr locked to 'none' — no inline style attributes remain")
        .contains("style-src-attr 'none'")
        .doesNotContain("style-src-attr 'unsafe-inline'");
    assertThat(csp).contains("script-src 'nonce-").contains("'strict-dynamic'");
  }

  /**
   * Verifies that the CSP {@code form-action} directive includes the Keycloak origin derived from
   * the {@code issuer-uri}, so the POST logout can redirect to Keycloak's {@code
   * end_session_endpoint}.
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
