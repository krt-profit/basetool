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
 * Integration tests for the {@code /actuator/loggers} authorization rule in {@link SecurityConfig}
 * (REQ-OBS-016).
 *
 * <p>The backend configures no separate management port, so Actuator rides the ordinary application
 * connector and every actuator path is evaluated by the main filter chain. Before this rule the
 * mutating {@code POST /actuator/loggers/{name}} fell through to {@code
 * anyRequest().authenticated()}, which means any validly-signed realm JWT — a plain member, a guest
 * — could raise the ROOT logger to {@code TRACE} and have Spring Security / WebClient / Netty write
 * bearer tokens and request bodies into the retained log stream. These tests pin the intended
 * matrix: anonymous is rejected, an authenticated non-admin is forbidden from writing but may still
 * read, and only {@code ROLE_ADMIN} may write.
 *
 * <p>The write assertions target a synthetic logger name so the test never perturbs the level of a
 * logger another test in the shared context depends on.
 */
@SpringBootTest
class ActuatorLoggersAuthorizationTest {

  /** Synthetic logger the write assertions target so no real logger's level is changed. */
  private static final String PROBE_LOGGER =
      "de.greluc.krt.profit.basetool.backend.test.LoggersAuthorizationProbe";

  /** Write path of the Actuator loggers endpoint for {@link #PROBE_LOGGER}. */
  private static final String PROBE_PATH = "/actuator/loggers/" + PROBE_LOGGER;

  /** Body of a level-change request; identical for every caller so only authorization varies. */
  private static final String LEVEL_BODY = "{\"configuredLevel\":\"INFO\"}";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  /** Builds a MockMvc instance with the real Spring Security filter chain applied. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithAnonymousUser
  void shouldRejectAnonymousLoggerWrite() throws Exception {
    // Given / When / Then: no credentials at all -> the RFC 7807 entry point answers 401.
    mockMvc
        .perform(post(PROBE_PATH).contentType(MediaType.APPLICATION_JSON).content(LEVEL_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = Roles.KRT_MEMBER)
  void shouldForbidAuthenticatedNonAdminLoggerWrite() throws Exception {
    // Given / When / Then: an ordinary authenticated member must NOT be able to raise a log level;
    // this is the exact case the `anyRequest().authenticated()` catch-all used to let through.
    mockMvc
        .perform(post(PROBE_PATH).contentType(MediaType.APPLICATION_JSON).content(LEVEL_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = Roles.OFFICER)
  void shouldForbidOfficerLoggerWrite() throws Exception {
    // Given / When / Then: OFFICER is the highest non-admin role and does not imply ADMIN in the
    // role hierarchy, so it must be refused too — the gate is admin-only, not "privileged-ish".
    mockMvc
        .perform(post(PROBE_PATH).contentType(MediaType.APPLICATION_JSON).content(LEVEL_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = Roles.ADMIN)
  void shouldAllowAdminLoggerWrite() throws Exception {
    // Given / When / Then: the runtime log-level control REQ-OBS-016 exists for must stay usable
    // for an admin. The endpoint's write operation returns no body -> 204.
    mockMvc
        .perform(post(PROBE_PATH).contentType(MediaType.APPLICATION_JSON).content(LEVEL_BODY))
        .andExpect(status().isNoContent());
  }

  @Test
  @WithMockUser(roles = Roles.KRT_MEMBER)
  void shouldStillAllowAuthenticatedNonAdminToReadLoggers() throws Exception {
    // Given / When / Then: only the mutator is admin-gated. The read keeps riding the authenticated
    // catch-all, so a non-admin can still see which levels are in effect.
    mockMvc.perform(get("/actuator/loggers")).andExpect(status().isOk());
  }
}
