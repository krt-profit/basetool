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
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipDto;
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
 * Security tests for {@link LeitungPageController} (REQ-ROLE-004): the page and its write proxies
 * admit only {@code ADMIN} and {@code OFFICER}, and {@code LOGISTICIAN} / {@code MISSION_MANAGER}
 * are forbidden.
 *
 * <p>Also pins that the Spezialkommando pane renders the roster and lead caps independently, and
 * that rank and group save on change without a per-row save button.
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
    when(backendApiClient.get("/api/v1/org-chart/leadership", LeitungViewDto.class))
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
   * @param skId the SK id
   * @param canAppointLead the lead-appointment cap
   * @param canManageRoster the roster cap
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
            List.of(new LeitungMemberDto(UUID.randomUUID(), "Pilot", "MEMBER", null, 0L, false)),
            List.of(),
            null);
    when(backendApiClient.get("/api/v1/org-chart/leadership", LeitungViewDto.class))
        .thenReturn(new LeitungViewDto(false, List.of(), List.of(), List.of(), List.of(sk)));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void page_skLead_managesRosterInPlaceWithoutLeadToggle() throws Exception {
    UUID skId = UUID.randomUUID();
    stubSpecialCommandView(skId, false, true);

    mockMvc
        .perform(get("/organisation/leitung"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-leitung-open=\"add-sk\"")))
        .andExpect(
            content()
                .string(containsString("data-remove-url=\"/organisation/special-commands/" + skId)))
        .andExpect(content().string(not(containsString("toggle-lead"))))
        .andExpect(content().string(not(containsString("/lead/ajax"))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void page_bereichsleiter_keepsLeadToggleWithoutMemberPageLink() throws Exception {
    UUID skId = UUID.randomUUID();
    stubSpecialCommandView(skId, true, false);

    mockMvc
        .perform(get("/organisation/leitung"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-sk-action=\"toggle-lead\"")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "data-lead-url=\"/organisation/leitung/special-commands/" + skId)))
        .andExpect(content().string(not(containsString("add-sk"))))
        .andExpect(content().string(not(containsString("/organisation/special-commands/"))));
  }

  /**
   * Stubs a view holding exactly one Staffel with the given capability flags and roster.
   *
   * @param admin whether the caller is an admin
   * @param canAppointLead the Staffelleiter-appointment cap
   * @param canManageRoster the lower-rank cap
   * @param members the roster rows
   */
  private void stubSquadronView(
      boolean admin,
      boolean canAppointLead,
      boolean canManageRoster,
      List<LeitungMemberDto> members) {
    LeitungUnitDto squadron =
        new LeitungUnitDto(
            UUID.randomUUID(),
            "Mamba",
            "MAM",
            OrgUnitKind.SQUADRON,
            canAppointLead,
            canManageRoster,
            members,
            List.of(),
            null);
    when(backendApiClient.get("/api/v1/org-chart/leadership", LeitungViewDto.class))
        .thenReturn(new LeitungViewDto(admin, List.of(), List.of(), List.of(squadron), List.of()));
  }

  /**
   * Counts the non-overlapping occurrences of {@code needle} in {@code haystack}.
   *
   * @param haystack the rendered page
   * @param needle the fragment to count
   * @return the number of occurrences
   */
  private static int count(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle);
        i >= 0;
        i = haystack.indexOf(needle, i + needle.length())) {
      n++;
    }
    return n;
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void page_staffelleiter_seesOwnSeatAsStaffelleiterReadOnly() throws Exception {
    stubSquadronView(
        false,
        false,
        true,
        List.of(
            new LeitungMemberDto(UUID.randomUUID(), "Lead", "STAFFELLEITER", null, 3L, true),
            new LeitungMemberDto(UUID.randomUUID(), "Pilot", "MEMBER", null, 0L, false)));

    String html =
        mockMvc
            .perform(get("/organisation/leitung"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertEquals(
        1, count(html, "class=\"leitung-rank-select\""), "only the plain member is editable");
    assertEquals(0, count(html, "save-rank"));
    assertEquals(0, count(html, "value=\"STAFFELLEITER\""));
    assertEquals(1, count(html, "chip chip--primary"));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void page_bereichsleiter_seesLowerRanksReadOnlyAndMayAppointStaffelleiter() throws Exception {
    stubSquadronView(
        false,
        true,
        false,
        List.of(
            new LeitungMemberDto(UUID.randomUUID(), "Kommando", "KOMMANDOLEITER", null, 1L, false),
            new LeitungMemberDto(UUID.randomUUID(), "Pilot", "MEMBER", null, 0L, false)));

    String html =
        mockMvc
            .perform(get("/organisation/leitung"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertEquals(1, count(html, "class=\"leitung-rank-select\""));
    assertEquals(1, count(html, "value=\"STAFFELLEITER\""));
    assertEquals(0, count(html, "value=\"KOMMANDOLEITER\""));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void page_readOnlyViewer_getsNoRankControls() throws Exception {
    stubSquadronView(
        false,
        false,
        false,
        List.of(new LeitungMemberDto(UUID.randomUUID(), "Lead", "STAFFELLEITER", null, 3L, false)));

    mockMvc
        .perform(get("/organisation/leitung"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("leitung-rank-select"))))
        .andExpect(content().string(not(containsString("save-rank"))))
        .andExpect(content().string(containsString("chip chip--primary")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void page_admin_mayEditOwnSeat() throws Exception {
    stubSquadronView(
        true,
        true,
        true,
        List.of(new LeitungMemberDto(UUID.randomUUID(), "Admin", "STAFFELLEITER", null, 3L, true)));

    mockMvc
        .perform(get("/organisation/leitung"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("leitung-rank-select")))
        .andExpect(content().string(containsString("data-leitung-action=\"clear-rank\"")))
        .andExpect(content().string(not(containsString("save-rank"))));
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
            eq("/api/v1/squadrons/{squadronId}/ranks/{userId}"),
            any(),
            eq(OrgUnitMembershipDto.class),
            eq(squadronId),
            eq(userId)))
        .thenReturn(
            new OrgUnitMembershipDto(
                userId, "Pilot", squadronId, OrgUnitKind.SQUADRON, false, false, false, null, 1L));

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
