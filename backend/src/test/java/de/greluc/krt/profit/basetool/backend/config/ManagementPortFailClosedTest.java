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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.platform.internal.ManagementPortSecurityConfig;
import de.greluc.krt.profit.basetool.backend.platform.internal.MonitoringScrapeProperties;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * Verifies that the backend's management-port chain stays fail-closed without a scrape credential
 * (REQ-OBS-005, ADR-0265): health is let through, metrics and info are refused.
 *
 * <p>Builds a context of the chain alone, because a further Spring Boot test context would exceed
 * the context budget (REQ-OPS-041).
 */
class ManagementPortFailClosedTest {

  private AnnotationConfigWebApplicationContext context;

  private MockMvc mockMvc;

  /** The management-port chain with an empty scrape credential, and nothing else. */
  @Configuration
  @EnableWebSecurity
  @Import(ManagementPortSecurityConfig.class)
  static class ChainOnly {

    /**
     * Supplies scrape properties with neither username nor password.
     *
     * @return the unconfigured scrape properties
     */
    @Bean
    MonitoringScrapeProperties monitoringScrapeProperties() {
      return new MonitoringScrapeProperties("", "");
    }
  }

  @BeforeEach
  void setUp() {
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("management.server.port", "0")));
    context.register(ChainOnly.class);
    context.refresh();
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @AfterEach
  void tearDown() {
    context.close();
  }

  @Test
  void healthIsLetThroughWithoutAScrapeCredential() throws Exception {
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound());
  }

  @Test
  void metricsAreRefusedWithoutAScrapeCredential() throws Exception {
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isForbidden());
  }

  @Test
  void infoIsRefusedWithoutAScrapeCredential() throws Exception {
    mockMvc.perform(get("/actuator/info")).andExpect(status().isForbidden());
  }
}
