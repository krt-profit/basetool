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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Integration tests for the configured {@code /actuator/prometheus} scrape chain (REQ-OBS-005):
 * only the basic-auth scrape identity may read it, no session is created, {@code /actuator/health}
 * stays public, and {@link BotProtectionFilter} lets the scrape through.
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
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(botProtectionFilter)
            .apply(springSecurity())
            .build();
  }

  @Test
  void shouldReject401WithoutCredentials() throws Exception {
    mockMvc
        .perform(get(PROMETHEUS))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
  }

  @Test
  void shouldServeMetricsWithValidBasicCredentials() throws Exception {
    mockMvc
        .perform(get(PROMETHEUS).with(httpBasic("metrics-scraper", "test-scrape-password")))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        .andExpect(content().string(containsString("application=\"basetool-frontend\"")))
        .andExpect(content().string(containsString("cache=\"squadronCatalogue\"")));
  }

  /**
   * A state-changing request is refused by CSRF even with the scrape identity, and sets no cookie.
   */
  @Test
  void shouldRejectPostWithValidBasicCredentialsByCsrf() throws Exception {
    mockMvc
        .perform(post(PROMETHEUS).with(httpBasic("metrics-scraper", "test-scrape-password")))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
  }

  @Test
  void shouldReject401WithWrongPassword() throws Exception {
    mockMvc
        .perform(get(PROMETHEUS).with(httpBasic("metrics-scraper", "wrong-password")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReject403WithLoggedInSessionUser() throws Exception {
    mockMvc
        .perform(get(PROMETHEUS).with(user("some-admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldKeepHealthEndpointReachableWithoutAuthentication() throws Exception {
    int status = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();

    org.assertj.core.api.Assertions.assertThat(status)
        .as("health endpoint must be reachable anonymously (200 UP or 503 DOWN, never 401/403)")
        .isIn(200, 503);
  }
}
