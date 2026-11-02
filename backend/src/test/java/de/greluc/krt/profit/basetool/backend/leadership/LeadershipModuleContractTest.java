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

package de.greluc.krt.profit.basetool.backend.leadership;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the leadership module's externally visible contract: the status and shape of the {@code
 * /api/v1/leadership} Leitung view per caller (REQ-ROLE-004).
 */
@SpringBootTest
@Transactional
class LeadershipModuleContractTest {

  private static final String LEITUNG_VIEW = "/api/v1/leadership";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void anonymousCallerIsUnauthorized() throws Exception {
    mockMvc.perform(get(LEITUNG_VIEW)).andExpect(status().isUnauthorized());
  }

  @Test
  void memberReadsTheLeitungViewAsNonAdmin() throws Exception {
    mockMvc
        .perform(get(LEITUNG_VIEW).with(member()).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.admin").value(false))
        .andExpect(jsonPath("$.organisationsleitungen").isArray())
        .andExpect(jsonPath("$.bereiche").isArray())
        .andExpect(jsonPath("$.squadrons").isArray())
        .andExpect(jsonPath("$.specialCommands").isArray());
  }

  @Test
  void adminReadsTheLeitungViewAsAdmin() throws Exception {
    mockMvc
        .perform(get(LEITUNG_VIEW).with(admin()).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.admin").value(true));
  }

  private static RequestPostProcessor member() {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_MEMBER"));
  }

  private static RequestPostProcessor admin() {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }
}
