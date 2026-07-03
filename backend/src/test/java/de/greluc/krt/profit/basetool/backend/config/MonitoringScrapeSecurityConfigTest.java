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

package de.greluc.krt.profit.basetool.backend.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration tests for the configured state of the {@code /actuator/prometheus} scrape chain
 * (REQ-OBS-005, epic #936 Phase 1): only the dedicated basic-auth identity may read the metrics
 * payload — anonymous callers, wrong passwords, raw bearer headers and even fully valid JWT
 * identities are rejected — while {@code /actuator/health} stays public for the Docker HEALTHCHECK.
 * Test-only credentials, never production values.
 */
@SpringBootTest(
    properties = {
      "app.monitoring.scrape.username=metrics-scraper",
      "app.monitoring.scrape.password=test-scrape-password"
    })
class MonitoringScrapeSecurityConfigTest {

  private static final String PROMETHEUS = "/actuator/prometheus";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void shouldReject401WithoutCredentials() throws Exception {
    // Given / When / Then
    mockMvc
        .perform(get(PROMETHEUS))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
  }

  @Test
  void shouldServeMetricsWithValidBasicCredentials() throws Exception {
    // Given / When / Then: the payload carries the module tag and the Caffeine cache meters
    // (backend `cities` cache) — the epic's original trigger.
    mockMvc
        .perform(get(PROMETHEUS).with(httpBasic("metrics-scraper", "test-scrape-password")))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        .andExpect(content().string(containsString("application=\"basetool-backend\"")))
        .andExpect(content().string(containsString("cache=\"cities\"")));
  }

  @Test
  void shouldReject401WithWrongPassword() throws Exception {
    // Given / When / Then
    mockMvc
        .perform(get(PROMETHEUS).with(httpBasic("metrics-scraper", "wrong-password")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReject401WithRawBearerHeader() throws Exception {
    // Given / When / Then: the scrape chain has no bearer-token support — a raw Authorization:
    // Bearer header is simply not an authentication here and the basic entry point answers 401.
    mockMvc
        .perform(get(PROMETHEUS).header(HttpHeaders.AUTHORIZATION, "Bearer some-token"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReject403WithValidJwtIdentity() throws Exception {
    // Given / When / Then: even a fully authenticated Keycloak user (valid JWT) must not read the
    // metrics payload — only the dedicated scrape identity counts (REQ-OBS-005).
    mockMvc
        .perform(get(PROMETHEUS).with(jwt().jwt(j -> j.subject("some-user"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldKeepHealthEndpointPublic() throws Exception {
    // Given / When / Then: regression guard — the Docker HEALTHCHECK relies on anonymous access.
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
