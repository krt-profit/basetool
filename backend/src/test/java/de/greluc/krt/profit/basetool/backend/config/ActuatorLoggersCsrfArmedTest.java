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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.support.Roles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves the runtime log-level change (REQ-OBS-016) works with CSRF enabled, as in production: the
 * bearer-only {@code /actuator/loggers/**} write must not demand a CSRF token, while an anonymous
 * caller and a non-exempt path stay refused.
 *
 * <p>Re-arms CSRF via {@code app.security.csrf.armed-in-test}; the {@code test} profile otherwise
 * disables it.
 */
@SpringBootTest(properties = "app.security.csrf.armed-in-test=true")
class ActuatorLoggersCsrfArmedTest {

  private static final String PROBE_PATH =
      "/actuator/loggers/de.greluc.krt.profit.basetool.backend.test.CsrfArmedProbe";

  private static final String LEVEL_BODY = "{\"configuredLevel\":\"INFO\"}";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  /** Builds a MockMvc instance with the real Spring Security filter chain applied. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = Roles.ADMIN)
  void adminLoggerWriteWithoutCsrfTokenSucceeds() throws Exception {
    mockMvc
        .perform(post(PROBE_PATH).contentType(MediaType.APPLICATION_JSON).content(LEVEL_BODY))
        .andExpect(status().isNoContent());
  }

  @Test
  @WithAnonymousUser
  void anonymousLoggerWriteWithoutTokenIsStillRefused() throws Exception {
    mockMvc
        .perform(post(PROBE_PATH).contentType(MediaType.APPLICATION_JSON).content(LEVEL_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = Roles.KRT_MEMBER)
  void nonAdminLoggerWriteWithoutTokenIsStillForbidden() throws Exception {
    mockMvc
        .perform(post(PROBE_PATH).contentType(MediaType.APPLICATION_JSON).content(LEVEL_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = Roles.ADMIN)
  void nonExemptPathStillDemandsACsrfToken() throws Exception {
    mockMvc
        .perform(post("/actuator/shutdown").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }
}
