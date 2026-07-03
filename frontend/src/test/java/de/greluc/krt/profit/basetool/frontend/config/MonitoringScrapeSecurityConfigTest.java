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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration tests for the configured state of the {@code /actuator/prometheus} scrape chain
 * (REQ-OBS-005, epic #936 Phase 1): only the dedicated basic-auth identity may read the metrics
 * payload — anonymous callers, wrong passwords and even a fully logged-in browser session are
 * rejected, no session cookie is ever minted for a scrape, {@code /actuator/health} stays public,
 * and the {@link BotProtectionFilter} (registered here exactly as in production) lets the
 * whitelisted scrape path through. Test-only credentials, never production values.
 */
@SpringBootTest(
    properties = {
      "app.monitoring.scrape.username=metrics-scraper",
      "app.monitoring.scrape.password=test-scrape-password"
    })
class MonitoringScrapeSecurityConfigTest {

  private static final String PROMETHEUS = "/actuator/prometheus";

  @Autowired private WebApplicationContext context;

  @Autowired private BotProtectionFilter botProtectionFilter;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    // The BotProtectionFilter is registered alongside the security chain, mirroring production
    // where the @Component filter also runs at the servlet-container level: without its
    // LEGITIMATE_PATHS whitelist entry, /actuator/prometheus would be answered 404 before the
    // scrape chain ever sees the request.
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(botProtectionFilter)
            .apply(springSecurity())
            .build();
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
    // Given / When / Then: the payload carries the module tag and the Caffeine `staticData` cache
    // meters — proof both that the BotProtectionFilter whitelist works and that the cache now
    // records statistics (recordStats(), the epic's original trigger).
    mockMvc
        .perform(get(PROMETHEUS).with(httpBasic("metrics-scraper", "test-scrape-password")))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        .andExpect(content().string(containsString("application=\"basetool-frontend\"")))
        .andExpect(content().string(containsString("cache=\"staticData\"")));
  }

  @Test
  void shouldReject401WithWrongPassword() throws Exception {
    // Given / When / Then
    mockMvc
        .perform(get(PROMETHEUS).with(httpBasic("metrics-scraper", "wrong-password")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReject403WithLoggedInSessionUser() throws Exception {
    // Given / When / Then: even a fully authenticated browser session (e.g. an admin) must not
    // read the metrics payload — only the dedicated scrape identity counts (REQ-OBS-005).
    mockMvc
        .perform(get(PROMETHEUS).with(user("some-admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldKeepHealthEndpointReachableWithoutAuthentication() throws Exception {
    // Given / When: regression guard — the Docker HEALTHCHECK relies on anonymous access, and the
    // health whitelist of the BotProtectionFilter must survive the prometheus addition. Under the
    // test profile the auto-configured Redis health indicator has no Redis to talk to, so the
    // aggregate may be 503 (DOWN) — the guard here is "no authentication gate", not
    // "everything UP".
    int status = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();

    // Then
    org.assertj.core.api.Assertions.assertThat(status)
        .as("health endpoint must be reachable anonymously (200 UP or 503 DOWN, never 401/403)")
        .isIn(200, 503);
  }
}
