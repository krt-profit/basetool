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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC-level test for {@link SpecialCommandMembersPageController}, the SK member page at {@code
 * /organisation/special-commands/{id}}. Pins the coarse frontend gate ({@code ADMIN} / {@code
 * OFFICER} in, a plain member out), the backend-403 → 403-page mapping that keeps an officer who
 * does not lead the SK out, the admin-vs-lead differences ({@code canToggleLead} renders the
 * admin-only lead-toggle column, {@code backUrl} points to the SK overview or the Leitung page),
 * the {@code membersResults} swap fragment (REQ-FE-005), and the member writes' redirect target and
 * AJAX relay (#582). The backend's per-SK {@code canManageMembers} verdict is the real authority;
 * here it is represented by the mocked backend's answers.
 */
@SpringBootTest
class SpecialCommandMembersPageControllerMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private final UUID skId = UUID.randomUUID();
  private final UUID memberId = UUID.randomUUID();

  /**
   * Wires MockMvc with the full Spring Security filter chain so {@code @PreAuthorize} is active.
   */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** Stubs the backend SK read and a one-member roster in the raw {@code Map} wire shape. */
  private void stubSpecialCommandWithOneMember() {
    Map<String, Object> sc = new HashMap<>();
    sc.put("id", skId.toString());
    sc.put("name", "Detail SK");
    sc.put("shorthand", "DSK");
    sc.put("description", "desc");
    sc.put("active", true);
    sc.put("isProfitEligible", false);
    sc.put("version", 0);
    Map<String, Object> member = new HashMap<>();
    member.put("userId", memberId.toString());
    member.put("userDisplayName", "Pilot");
    member.put("orgUnitId", skId.toString());
    member.put("kind", "SPECIAL_COMMAND");
    member.put("isLogistician", false);
    member.put("isMissionManager", false);
    member.put("isLead", false);
    member.put("version", 3);
    when(backendApiClient.get(eq("/api/v1/special-commands/" + skId), anyTypeRef())).thenReturn(sc);
    when(backendApiClient.get(eq("/api/v1/special-commands/" + skId + "/members"), anyTypeRef()))
        .thenReturn(List.of(member));
  }

  // An admin gets the full page with the admin-only lead-toggle column (posting to the admin
  // area) and a back link to the SK overview.
  @Test
  @WithMockUser(roles = "ADMIN")
  void detail_admin_rendersLeadToggleAndBackToOverview() throws Exception {
    stubSpecialCommandWithOneMember();

    mockMvc
        .perform(get("/organisation/special-commands/" + skId))
        .andExpect(status().isOk())
        .andExpect(view().name("organisation/special-command-detail"))
        .andExpect(model().attribute("canToggleLead", true))
        .andExpect(model().attribute("backUrl", "/admin/special-commands"))
        .andExpect(
            content()
                .string(
                    containsString(
                        "/admin/special-commands/" + skId + "/members/" + memberId + "/lead")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "/organisation/special-commands/"
                            + skId
                            + "/members/"
                            + memberId
                            + "/flags")));
  }

  // An officer the backend admits (the SK's lead) gets the page without the lead toggle and with a
  // back link to the Leitung page.
  @Test
  @WithMockUser(roles = "OFFICER")
  void detail_officer_rendersWithoutLeadToggleAndBackToLeitung() throws Exception {
    stubSpecialCommandWithOneMember();

    mockMvc
        .perform(get("/organisation/special-commands/" + skId))
        .andExpect(status().isOk())
        .andExpect(view().name("organisation/special-command-detail"))
        .andExpect(model().attribute("canToggleLead", false))
        .andExpect(model().attribute("backUrl", "/organisation/leitung"))
        .andExpect(content().string(not(containsString("/members/" + memberId + "/lead"))))
        .andExpect(content().string(containsString("id=\"add-member-modal\"")));
  }

  // A plain member never reaches the page shell: the class-level ADMIN_OR_OFFICER gate refuses
  // before any backend call.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void detail_member_returns403WithoutBackendCall() throws Exception {
    mockMvc
        .perform(get("/organisation/special-commands/" + skId))
        .andExpect(status().isForbidden());

    verify(backendApiClient, never()).get(eq("/api/v1/special-commands/" + skId), anyTypeRef());
  }

  // An officer who does not lead this SK passes the coarse gate, but the backend refuses the SK
  // read; the page answers with the standard 403 page instead of a redirect.
  @Test
  @WithMockUser(roles = "OFFICER")
  void detail_backendRefuses_returns403Page() throws Exception {
    when(backendApiClient.get(eq("/api/v1/special-commands/" + skId), anyTypeRef()))
        .thenThrow(new BackendServiceException("forbidden", null, 403));

    mockMvc
        .perform(get("/organisation/special-commands/" + skId))
        .andExpect(status().isForbidden());
  }

  // Any other backend failure keeps the old behaviour: redirect back with an error parameter —
  // for a non-admin back to the Leitung page.
  @Test
  @WithMockUser(roles = "OFFICER")
  void detail_backendFails_redirectsToBackUrlWithError() throws Exception {
    when(backendApiClient.get(eq("/api/v1/special-commands/" + skId), anyTypeRef()))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(get("/organisation/special-commands/" + skId))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/organisation/leitung?error=LoadSpecialCommandDetailFailed"));
  }

  // covers #582 / REQ-FE-005 — the member-roster fragment GET renders only the membersResults
  // fragment; the add-member modal (which lives outside the fragment) is not present.
  @Test
  @WithMockUser(roles = "ADMIN")
  void detail_fragmentMembers_rendersOnlyMembersFragment() throws Exception {
    stubSpecialCommandWithOneMember();

    mockMvc
        .perform(get("/organisation/special-commands/" + skId).param("fragment", "members"))
        .andExpect(status().isOk())
        .andExpect(view().name("organisation/special-command-detail :: membersResults"))
        .andExpect(content().string(containsString("Pilot")))
        .andExpect(content().string(not(containsString("add-member-modal"))));
  }

  // covers #582 — the add-member twin relays to the backend and returns 200 for an officer the
  // backend admits; a roster mutation does not touch the SK catalogue, so it does not evict.
  @Test
  @WithMockUser(roles = "OFFICER")
  void addMemberAjax_officer_returns200WithoutEviction() throws Exception {
    UUID userId = UUID.randomUUID();
    when(backendApiClient.post(
            eq("/api/v1/special-commands/" + skId + "/members/" + userId), any(), eq(Void.class)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/organisation/special-commands/" + skId + "/members")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("userId", userId.toString()))
        .andExpect(status().isOk());

    verify(backendApiClient, never()).evict(CacheDomain.SQUADRON, CacheDomain.ORG_UNIT);
  }

  // A backend refusal of a member write (not the lead of this SK) is relayed as 403 so krtFetch
  // shows the error instead of re-swapping.
  @Test
  @WithMockUser(roles = "OFFICER")
  void removeMemberAjax_backendRefuses_relays403() throws Exception {
    when(backendApiClient.delete(
            eq("/api/v1/special-commands/" + skId + "/members/" + memberId), eq(Void.class)))
        .thenThrow(new BackendServiceException("forbidden", null, 403));

    mockMvc
        .perform(
            post("/organisation/special-commands/" + skId + "/members/" + memberId + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  // The classic (no-JS) flags form redirects back to the SK member page, not the admin area.
  @Test
  @WithMockUser(roles = "OFFICER")
  void patchMemberFlags_classic_redirectsToMemberPage() throws Exception {
    when(backendApiClient.patch(contains("/members/" + memberId), any(), eq(Void.class)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/organisation/special-commands/" + skId + "/members/" + memberId + "/flags")
                .with(csrf())
                .param("isLogistician", "true")
                .param("_isLogistician", "on")
                .param("_isMissionManager", "on")
                .param("version", "3"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/organisation/special-commands/" + skId));
  }
}
