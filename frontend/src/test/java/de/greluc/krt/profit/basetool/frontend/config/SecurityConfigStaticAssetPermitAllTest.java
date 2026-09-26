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
 * Verifies that {@link SecurityConfig} lets anonymous static-asset requests through without
 * redirecting them to {@link SsoReAuthenticationEntryPoint}.
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
   * Verifies that none of the asset paths (sourcemaps, favicon, robots, stylesheet, error subpath,
   * web manifest) is redirected; the status itself is not asserted, only that {@link
   * MockHttpServletResponse#getRedirectedUrl()} stays {@code null}.
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
        "/error/foo",
        "/manifest.webmanifest"
      })
  @WithAnonymousUser
  void anonymousGetOnStaticAssetPath_doesNotRedirectToOAuth2Login(String path) throws Exception {
    MockHttpServletResponse response = mockMvc.perform(get(path)).andReturn().getResponse();

    assertNull(
        response.getRedirectedUrl(),
        "Path "
            + path
            + " unexpectedly redirected to "
            + response.getRedirectedUrl()
            + " — Spring Security should not gate static-asset paths.");
  }
}
