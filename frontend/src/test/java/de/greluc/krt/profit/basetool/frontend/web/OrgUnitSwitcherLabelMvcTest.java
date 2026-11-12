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

package de.greluc.krt.profit.basetool.frontend.web;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
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
 * Verifies the sidebar org-unit switcher: its no-pin row label, which differs for admins (every org
 * unit) and other callers (their own reach), and its selected row and the title, which follow the
 * backend's effective context (REQ-ORG-024).
 *
 * <p>Renders the page because the label is chosen by {@code sec:authorize} expressions that only
 * fail at render time. The switcher needs at least two options to render.
 */
@SpringBootTest
class OrgUnitSwitcherLabelMvcTest {

  /** Matches a rendered page whose „Alle meine Org-Einheiten" row is the selected one. */
  private static final String ALL_MINE_SELECTED =
      "(?s).*<option value=\"\"\\s+selected=\"selected\">Alle meine Org-Einheiten</option>.*";

  private static final OrgUnitMembershipOptionDto HEL =
      new OrgUnitMembershipOptionDto(UUID.randomUUID(), "Hel", "HEL", "SQUADRON");

  private static final OrgUnitMembershipOptionDto ARGON =
      new OrgUnitMembershipOptionDto(UUID.randomUUID(), "Argon", "ARG", "SPECIAL_COMMAND");

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
                    new OrgUnitMembershipOptionDto(UUID.randomUUID(), "ALPHA", "ALP", "SQUADRON"),
                    new OrgUnitMembershipOptionDto(
                        UUID.randomUUID(), "VANGUARD", "VGD", "SQUADRON"))));
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
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN", "MISSION_MANAGER"})
  void switcher_officerIsNotAnAdminHere() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Alle meine Org-Einheiten")));
  }

  /**
   * An unpinned member of a Staffel and an SK sees the union of both: the switcher selects „Alle
   * meine Org-Einheiten" and no single unit, the title names the union, and the page announces it.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void switcher_unpinnedMemberOfTwoUnits_showsAllMyOrgUnitsSelected() throws Exception {
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(
            LayoutResponses.context(
                LayoutContextLoader.ContextKind.ALL_MY_ORG_UNITS, null, List.of(HEL, ARGON)));

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.matchesPattern(ALL_MINE_SELECTED)))
        .andExpect(content().string(Matchers.not(Matchers.matchesPattern(unitSelected(HEL)))))
        .andExpect(content().string(Matchers.containsString("Profit Basetool – Alle meine")))
        .andExpect(
            content()
                .string(
                    Matchers.containsString("data-active-org-unit-context=\"ALL_MY_ORG_UNITS\"")));
  }

  /** A pin the backend honoured is the selected row, whatever its kind, and the title names it. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void switcher_memberPinnedToTheSk_showsTheSkSelected() throws Exception {
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(
            LayoutResponses.context(
                LayoutContextLoader.ContextKind.ORG_UNIT, ARGON.orgUnitId(), List.of(HEL, ARGON)));

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.matchesPattern(unitSelected(ARGON))))
        .andExpect(content().string(Matchers.not(Matchers.matchesPattern(ALL_MINE_SELECTED))))
        .andExpect(content().string(Matchers.containsString("Profit Basetool – ARG")));
  }

  /**
   * Matches a rendered page whose row of the given unit is the selected one.
   *
   * @param unit the switcher row
   * @return the pattern
   */
  private static String unitSelected(OrgUnitMembershipOptionDto unit) {
    return "(?s).*<option value=\"" + unit.orgUnitId() + "\"\\s+selected=\"selected\">.*";
  }
}
