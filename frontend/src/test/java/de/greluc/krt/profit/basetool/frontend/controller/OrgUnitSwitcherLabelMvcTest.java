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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the sidebar org-unit switcher's no-pin row label, which differs for admins (every org
 * unit) and other callers (their own reach).
 *
 * <p>Renders the page because the label is chosen by {@code sec:authorize} expressions that only
 * fail at render time. The switcher needs at least two options to render.
 */
@SpringBootTest
class OrgUnitSwitcherLabelMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(
            LayoutResponses.orgUnits(
                List.of(
                    new OrgUnitMembershipOptionDto(
                        UUID.randomUUID(), "IRIDIUM", "IRI", "SQUADRON", true),
                    new OrgUnitMembershipOptionDto(
                        UUID.randomUUID(), "VANGUARD", "VGD", "SQUADRON", true))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void switcher_admin_offersEveryOrgUnitAndSaysSo() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Alle Org-Einheiten")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("Alle meine"))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void switcher_member_saysTheUnionIsTheirOwn() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Alle meine Org-Einheiten")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void switcher_officerIsNotAnAdminHere() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Alle meine Org-Einheiten")));
  }
}
