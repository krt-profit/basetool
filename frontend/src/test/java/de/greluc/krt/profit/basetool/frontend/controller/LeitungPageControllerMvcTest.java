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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.LeitungMemberDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LeitungUnitDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LeitungViewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC-level security test for {@link LeitungPageController} (epic #800, REQ-ROLE-004). Pins the
 * page's role gate to {@code ADMIN} / {@code OFFICER} ({@code Roles.ADMIN_OR_OFFICER}): every
 * functional leader carries the operative {@code OFFICER} grant, so an officer (and admin) reaches
 * the page and its write proxies, while the previously-accepted {@code LOGISTICIAN} / {@code
 * MISSION_MANAGER} capability roles — which have no appointment reach and only ever saw an empty
 * page — are now forbidden. The delegated per-unit authority still lives at the backend appointment
 * endpoints; this test only fixes the coarse frontend gate that used to admit them.
 *
 * <p>It also pins how the Spezialkommando section renders the two caps: the roster cap links to the
 * SK member page, the lead cap shows the lead toggle, and neither leaks into the other.
 */
@SpringBootTest
class LeitungPageControllerMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  /**
   * Wires MockMvc with the full Spring Security filter chain so {@code @PreAuthorize} is active.
   */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** Returns an empty delegated view so the page renders without any manageable unit. */
  private void stubEmptyView() {
    when(backendApiClient.get("/api/v1/leitung/view", LeitungViewDto.class))
        .thenReturn(new LeitungViewDto(false, List.of(), List.of(), List.of(), List.of()));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void page_officer_returns200() throws Exception {
    stubEmptyView();

    mockMvc.perform(get("/organisation/leitung")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void page_admin_returns200() throws Exception {
    stubEmptyView();

    mockMvc.perform(get("/organisation/leitung")).andExpect(status().isOk());
  }

  /**
   * Stubs a view holding exactly one Spezialkommando with one member and the given capability
   * flags.
   *
   * @param skId the SK id.
   * @param canAppointLead the lead-appointment cap (parent Bereichsleiter / admin).
   * @param canManageRoster the roster cap (the SK's own lead / admin).
   */
  private void stubSpecialCommandView(UUID skId, boolean canAppointLead, boolean canManageRoster) {
    LeitungUnitDto sk =
        new LeitungUnitDto(
            skId,
            "Alpha SK",
            "ASK",
            OrgUnitKind.SPECIAL_COMMAND,
            canAppointLead,
            canManageRoster,
            List.of(new LeitungMemberDto(UUID.randomUUID(), "Pilot", "MEMBER", null, 0L)),
            List.of(),
            null);
    when(backendApiClient.get("/api/v1/leitung/view", LeitungViewDto.class))
        .thenReturn(new LeitungViewDto(false, List.of(), List.of(), List.of(), List.of(sk)));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void page_skLead_linksToMemberPageWithoutLeadToggle() throws Exception {
    UUID skId = UUID.randomUUID();
    stubSpecialCommandView(skId, false, true);

    mockMvc
        .perform(get("/organisation/leitung"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(containsString("href=\"/organisation/special-commands/" + skId + "\"")))
        .andExpect(content().string(not(containsString("toggle-sk-lead"))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void page_bereichsleiter_keepsLeadToggleWithoutMemberPageLink() throws Exception {
    UUID skId = UUID.randomUUID();
    stubSpecialCommandView(skId, true, false);

    mockMvc
        .perform(get("/organisation/leitung"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("toggle-sk-lead")))
        .andExpect(content().string(not(containsString("/organisation/special-commands/"))));
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void page_logistician_returns403() throws Exception {
    mockMvc.perform(get("/organisation/leitung")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void page_missionManager_returns403() throws Exception {
    mockMvc.perform(get("/organisation/leitung")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void page_member_returns403() throws Exception {
    mockMvc.perform(get("/organisation/leitung")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void assignSquadronRank_officer_returns200() throws Exception {
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/squadrons/" + squadronId + "/ranks/" + userId), any(), eq(Object.class)))
        .thenReturn(new Object());

    mockMvc
        .perform(
            put("/organisation/leitung/squadrons/" + squadronId + "/ranks/" + userId + "/ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"KOMMANDOLEITER\",\"version\":0}"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void assignSquadronRank_logistician_returns403() throws Exception {
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    mockMvc
        .perform(
            put("/organisation/leitung/squadrons/" + squadronId + "/ranks/" + userId + "/ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"KOMMANDOLEITER\",\"version\":0}"))
        .andExpect(status().isForbidden());
  }
}
