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
 * MVC test for {@link SpecialCommandMembersPageController}: the admin/officer gate, the mapping of
 * a backend 403, the admin-versus-lead differences, the {@code membersResults} fragment
 * (REQ-FE-005) and the member writes' redirect and AJAX relay.
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

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void detail_member_returns403WithoutBackendCall() throws Exception {
    mockMvc
        .perform(get("/organisation/special-commands/" + skId))
        .andExpect(status().isForbidden());

    verify(backendApiClient, never()).get(eq("/api/v1/special-commands/" + skId), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void detail_backendRefuses_returns403Page() throws Exception {
    when(backendApiClient.get(eq("/api/v1/special-commands/" + skId), anyTypeRef()))
        .thenThrow(new BackendServiceException("forbidden", null, 403));

    mockMvc
        .perform(get("/organisation/special-commands/" + skId))
        .andExpect(status().isForbidden());
  }

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
