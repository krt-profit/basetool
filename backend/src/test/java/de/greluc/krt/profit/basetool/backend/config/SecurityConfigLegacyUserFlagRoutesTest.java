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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins that the removed endpoints {@code PATCH /api/v1/users/{id}/logistician} and {@code
 * .../mission-manager} stay closed: the ADMIN-only {@code /api/v1/users/**} catch-all refuses
 * non-admins, and admins reach no handler.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Retired per-user flag routes")
class SecurityConfigLegacyUserFlagRoutesTest {

  @Autowired private WebApplicationContext context;

  /** Mocked so the context starts without reaching a Keycloak JWKS endpoint. */
  @MockitoBean private JwtDecoder jwtDecoder;

  private MockMvc mockMvc;

  /** Builds MockMvc over the real filter chain, so the matchers under test are the ones applied. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * An OFFICER is refused by the catch-all before any handler.
   *
   * @param flag the removed path segment
   * @throws Exception when MockMvc fails to perform the request
   */
  @ParameterizedTest(name = "PATCH .../{0} as OFFICER is 403")
  @ValueSource(strings = {"logistician", "mission-manager"})
  void officerIsStillRefusedByTheChain(String flag) throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/users/" + UUID.randomUUID() + "/" + flag)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  /**
   * A member is refused the same way.
   *
   * @param flag the retired path segment
   * @throws Exception when MockMvc fails to perform the request
   */
  @ParameterizedTest(name = "PATCH .../{0} as KRT_MEMBER is 403")
  @ValueSource(strings = {"logistician", "mission-manager"})
  void memberIsStillRefusedByTheChain(String flag) throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/users/" + UUID.randomUUID() + "/" + flag)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  /**
   * An ADMIN passes the chain and finds nothing behind it — the proof that the deleted matchers
   * guarded no endpoint.
   *
   * @param flag the retired path segment
   * @throws Exception when MockMvc fails to perform the request
   */
  @ParameterizedTest(name = "PATCH .../{0} as ADMIN reaches no handler")
  @ValueSource(strings = {"logistician", "mission-manager"})
  void adminReachesNoHandler(String flag) throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/users/" + UUID.randomUUID() + "/" + flag)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }
}
