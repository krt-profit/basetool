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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration tests for the fail-closed default of the {@code /actuator/prometheus} scrape chain
 * (REQ-OBS-005): with {@code MONITORING_SCRAPE_USER}/{@code MONITORING_SCRAPE_PASSWORD} unset (the
 * state of dev, test, e2e and prod before the monitoring rollout) the endpoint denies every request
 * — there is no unauthenticated fallback, and presented credentials are not even evaluated because
 * no authentication mechanism is wired. The blank credentials are pinned as test properties (which
 * outrank the OS environment) so the class stays hermetic even on a machine that exports the {@code
 * MONITORING_SCRAPE_*} variables, e.g. for a local monitoring stack.
 */
@SpringBootTest(properties = {"app.monitoring.scrape.username=", "app.monitoring.scrape.password="})
class MonitoringScrapeSecurityFailClosedTest {

  private static final String PROMETHEUS = "/actuator/prometheus";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void shouldDenyAnonymousRequestWhenNoCredentialsConfigured() throws Exception {
    // Given / When / Then
    mockMvc.perform(get(PROMETHEUS)).andExpect(status().isForbidden());
  }

  @Test
  void shouldDenyEvenWellFormedBasicCredentialsWhenNoneConfigured() throws Exception {
    // Given / When / Then: without configured credentials no authentication mechanism exists on
    // the chain — presented basic credentials must not open the endpoint.
    mockMvc
        .perform(get(PROMETHEUS).with(httpBasic("metrics-scraper", "any-password")))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldKeepHealthEndpointPublic() throws Exception {
    // Given / When / Then: the fail-closed scrape chain must not affect the Docker HEALTHCHECK.
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
