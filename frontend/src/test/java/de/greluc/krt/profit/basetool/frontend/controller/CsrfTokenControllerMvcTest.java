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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Verifies {@link CsrfTokenController} against the real {@link
 * de.greluc.krt.profit.basetool.frontend.config.SecurityConfig} filter chain (covers REQ-FE-004 /
 * REQ-SEC-010): an authenticated session is handed the active CSRF header name + token, while an
 * anonymous caller is bounced to the OIDC entry point and never receives a token. The full filter
 * chain is exercised (via {@code springSecurity()}) so the {@code CsrfFilter} actually populates
 * the {@code CsrfToken} request attribute the controller resolves.
 */
@SpringBootTest
@ActiveProfiles("test")
class CsrfTokenControllerMvcTest {

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

  /** An authenticated GET /csrf returns the active CSRF header name and a non-empty token. */
  @Test
  @WithMockUser
  void authenticatedGetCsrf_returnsHeaderNameAndToken() throws Exception {
    mockMvc
        .perform(get("/csrf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headerName").isNotEmpty())
        .andExpect(jsonPath("$.token").isNotEmpty());
  }

  /**
   * An anonymous GET /csrf must not hand out a token. It falls under the {@code authenticated()}
   * catch-all, so {@link
   * de.greluc.krt.profit.basetool.frontend.config.SsoReAuthenticationEntryPoint} responds (a
   * redirect to the OIDC login) rather than 200 — asserting "not 200" keeps the test robust to the
   * exact entry-point status while still pinning the security-relevant contract: anonymous callers
   * get no token.
   */
  @Test
  @WithAnonymousUser
  void anonymousGetCsrf_doesNotReturnToken() throws Exception {
    int statusCode = mockMvc.perform(get("/csrf")).andReturn().getResponse().getStatus();
    assertNotEquals(
        HttpStatus.OK.value(),
        statusCode,
        "Anonymous GET /csrf must not return 200 with a token — it must hit the auth entry point.");
  }
}
