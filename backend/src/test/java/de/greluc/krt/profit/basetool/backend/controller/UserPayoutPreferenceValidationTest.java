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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies over the full MockMvc stack that {@code PUT /api/v1/users/me/payout-preference} answers
 * 400 when {@code preference} or {@code version} is missing.
 */
@SpringBootTest
class UserPayoutPreferenceValidationTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private OrgUnitMembershipService orgUnitMembershipService;
  @MockitoBean private JwtDecoder jwtDecoder;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * A body that omits {@code preference} (only {@code version} present) must be rejected with 400
   * by the {@code @NotNull} on {@code MyPayoutPreferenceRequest.preference} — the service is never
   * reached.
   *
   * @throws Exception MockMvc plumbing.
   */
  @Test
  void updateMyPayoutPreference_missingPreference_isBadRequest() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/users/me/payout-preference")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}")
                .with(jwt()))
        .andExpect(status().isBadRequest());
  }

  /**
   * A body that omits {@code version} (only {@code preference} present) must be rejected with 400
   * by the {@code @NotNull} on {@code MyPayoutPreferenceRequest.version} — the optimistic-lock
   * token is mandatory, so a write without it cannot slip through.
   *
   * @throws Exception MockMvc plumbing.
   */
  @Test
  void updateMyPayoutPreference_missingVersion_isBadRequest() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/users/me/payout-preference")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preference\":\"DONATE\"}")
                .with(jwt()))
        .andExpect(status().isBadRequest());
  }
}
