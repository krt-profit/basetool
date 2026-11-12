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

package de.greluc.krt.profit.basetool.frontend.orgunit.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.MeLayoutResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SpecialCommandDto;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC test for {@link SpecialCommandMembersPageController}: the admin/officer gate, the mapping of
 * a backend 403, the admin-versus-lead differences — a non-admin lead's own row and a lead's row
 * render read-only (REQ-ROLE-004) — the {@code membersResults} fragment (REQ-FE-005) and the member
 * writes' redirect and AJAX relay.
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

  /** Stubs the backend SK read and a one-member roster as the client decodes them. */
  private void stubSpecialCommandWithOneMember() {
    stubSpecialCommandWith(
        new OrgUnitMembershipDto(
            memberId, "Pilot", skId, OrgUnitKind.SPECIAL_COMMAND, false, false, false, null, 3L));
  }

  /** Stubs the backend layout so the caller holds officer reach, which the page gate reads. */
  private void stubOfficerReach() {
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(LayoutResponses.officerReach(true));
  }

  /**
   * Stubs the backend SK read and the given roster.
   *
   * @param roster the roster rows as the client decodes them
   */
  private void stubSpecialCommandWith(OrgUnitMembershipDto... roster) {
    when(backendApiClient.get(
            eq("/api/v1/special-commands/{id}"), eq(SpecialCommandDto.class), eq(skId)))
        .thenReturn(new SpecialCommandDto(skId, "Detail SK", "DSK", "desc", true, 0L));
    when(backendApiClient.get(eq("/api/v1/special-commands/{id}/members"), anyTypeRef(), eq(skId)))
        .thenReturn(List.of(roster));
  }

  /**
   * A roster row of the SK.
   *
   * @param userId the member
   * @param name the member's display name
   * @param logistician the Logistiker flag
   * @param missionManager the Einsatzleiter flag
   * @param lead whether the member holds the SK lead seat
   * @return the row
   */
  private OrgUnitMembershipDto row(
      UUID userId, String name, boolean logistician, boolean missionManager, boolean lead) {
    return new OrgUnitMembershipDto(
        userId,
        name,
        skId,
        OrgUnitKind.SPECIAL_COMMAND,
        logistician,
        missionManager,
        lead,
        null,
        1L);
  }

  /**
   * A signed-in member with the given account id and authority, so the page knows the caller's own
   * row.
   *
   * @param userId the caller's account id
   * @param authority the caller's authority
   * @return the request post-processor carrying the OIDC login
   */
  private static RequestPostProcessor signedIn(UUID userId, String authority) {
    return oidcLogin()
        .idToken(token -> token.subject(userId.toString()))
        .authorities(new SimpleGrantedAuthority(authority));
  }

  /**
   * Renders the member page in German for the given caller and returns its roster table.
   *
   * @param caller the signed-in caller
   * @return the markup of the roster table
   * @throws Exception if the request fails
   */
  private String rosterFor(RequestPostProcessor caller) throws Exception {
    String html =
        mockMvc
            .perform(
                get("/organisation/special-commands/" + skId).locale(Locale.GERMAN).with(caller))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    int start = html.indexOf("data-testid=\"sk-roster\"");
    assertThat(start).isNotNegative();
    return html.substring(start, html.indexOf("</table>", start));
  }

  /**
   * A non-admin lead may not write their own row nor a lead's row (REQ-ROLE-004): both render their
   * set flags as chips, without a flag form and without „Entfernen", while a plain member's row
   * keeps both.
   *
   * @throws Exception if the request fails
   */
  @Test
  void detail_lead_rendersTheirOwnAndACoLeadsRowReadOnly() throws Exception {
    UUID leadId = UUID.randomUUID();
    UUID coLeadId = UUID.randomUUID();
    stubOfficerReach();
    stubSpecialCommandWith(
        row(leadId, "Lead", true, false, true),
        row(coLeadId, "CoLead", false, true, true),
        row(memberId, "Pilot", false, false, false));

    String roster = rosterFor(signedIn(leadId, "ROLE_KRT_MEMBER"));

    assertThat(roster.split("class=\"unit-flag-form\"", -1)).hasSize(3);
    assertThat(roster.split("data-sk-action=\"remove\"", -1)).hasSize(2);
    assertThat(roster)
        .contains("/members/" + memberId + "/flags")
        .contains("/members/" + memberId + "/delete")
        .doesNotContain("/members/" + leadId + "/flags")
        .doesNotContain("/members/" + leadId + "/delete")
        .doesNotContain("/members/" + coLeadId + "/flags")
        .doesNotContain("/members/" + coLeadId + "/delete")
        .contains("<span class=\"chip\">Logistiker</span>")
        .contains("<span class=\"chip\">Einsatzleiter</span>");
  }

  /**
   * An admin writes every row, their own and a lead's included.
   *
   * @throws Exception if the request fails
   */
  @Test
  void detail_admin_keepsTheirOwnAndALeadsRowWritable() throws Exception {
    UUID adminId = UUID.randomUUID();
    UUID leadId = UUID.randomUUID();
    stubSpecialCommandWith(
        row(adminId, "Admin", false, false, false), row(leadId, "Lead", true, false, true));

    String roster = rosterFor(signedIn(adminId, "ROLE_ADMIN"));

    assertThat(roster.split("class=\"unit-flag-form\"", -1)).hasSize(5);
    assertThat(roster.split("data-sk-action=\"remove\"", -1)).hasSize(3);
    assertThat(roster)
        .contains("/members/" + adminId + "/flags")
        .contains("/members/" + leadId + "/delete")
        .doesNotContain("<span class=\"chip\">");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void detail_admin_rendersLeadToggleAndBackToOverview() throws Exception {
    stubSpecialCommandWithOneMember();

    mockMvc
        .perform(get("/organisation/special-commands/" + skId))
        .andExpect(status().isOk())
        .andExpect(view().name("orgunit/organisation/special-command-detail"))
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

  /**
   * The member page renders the shared unit detail: page head with a back link, one primary action,
   * the members tab and the roster with role flags, the lead flag and a row menu.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void detail_rendersTheSharedUnitDetail() throws Exception {
    stubSpecialCommandWithOneMember();

    String html =
        mockMvc
            .perform(get("/organisation/special-commands/" + skId).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\" href=\"/admin/special-commands\"")
        .containsPattern("<h1>Detail SK</h1>")
        .contains("id=\"members-box\"")
        .containsPattern("id=\"add-member-btn\" class=\"btn btn--cta\"")
        .contains("Spezialkommando · DSK")
        .contains("data-testid=\"sk-roster\"")
        .contains("data-testid=\"sk-member-row\"")
        .containsPattern("data-sk-flag=\"isLogistician\"\\s+aria-pressed=\"false\"")
        .contains("class=\"matrix-flag unit-roster__lead\"")
        .contains("data-sk-action=\"toggle-lead\"")
        .contains("data-sk-action=\"remove\"")
        .contains("data-refresh-container=\"#members-results\"")
        .doesNotContain("hud-box")
        .doesNotContain("class=\"greeting")
        .doesNotContain("use href=\"#krt-icon-save\"");
    assertThat(html.split("btn--cta", -1)).hasSizeLessThanOrEqualTo(3);
    assertThat(html.substring(html.indexOf("<main"), html.indexOf("</main>")))
        .doesNotContain("krtm-");
  }

  /**
   * Every roster write stays a classic form post for a browser without JavaScript (REQ-FE-001):
   * each flag button submits the toggled pair to the flags handler, and removal and the admin lead
   * toggle submit to their handlers.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void detail_rosterWritesKeepTheirNoScriptForms() throws Exception {
    stubSpecialCommandWithOneMember();

    String html =
        mockMvc
            .perform(get("/organisation/special-commands/" + skId).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html.split("class=\"unit-flag-form\"", -1)).hasSize(3);
    assertThat(html)
        .containsPattern("action=\"/organisation/special-commands/[^\"]+/members/[^\"]+/flags\"")
        .containsPattern("action=\"/organisation/special-commands/[^\"]+/members/[^\"]+/delete\"")
        .containsPattern("action=\"/admin/special-commands/[^\"]+/members/[^\"]+/lead\"")
        .containsPattern("type=\"submit\" class=\"unit-flag\" data-sk-flag=\"isLogistician\"");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void detail_officer_rendersWithoutLeadToggleAndBackToLeitung() throws Exception {
    stubOfficerReach();
    stubSpecialCommandWithOneMember();

    mockMvc
        .perform(get("/organisation/special-commands/" + skId))
        .andExpect(status().isOk())
        .andExpect(view().name("orgunit/organisation/special-command-detail"))
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

    verify(backendApiClient, never())
        .get(eq("/api/v1/special-commands/{id}"), eq(SpecialCommandDto.class), eq(skId));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void detail_backendRefuses_returns403Page() throws Exception {
    stubOfficerReach();
    when(backendApiClient.get(
            eq("/api/v1/special-commands/{id}"), eq(SpecialCommandDto.class), eq(skId)))
        .thenThrow(new BackendServiceException("forbidden", null, 403));

    mockMvc
        .perform(get("/organisation/special-commands/" + skId))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void detail_backendFails_redirectsToBackUrlWithError() throws Exception {
    stubOfficerReach();
    when(backendApiClient.get(
            eq("/api/v1/special-commands/{id}"), eq(SpecialCommandDto.class), eq(skId)))
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
        .andExpect(view().name("orgunit/organisation/special-command-detail :: membersResults"))
        .andExpect(content().string(containsString("Pilot")))
        .andExpect(content().string(not(containsString("add-member-modal"))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void addMemberAjax_officer_returns200WithoutEviction() throws Exception {
    stubOfficerReach();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.post(
            eq("/api/v1/special-commands/{id}/members/{userId}"),
            any(),
            eq(Void.class),
            eq(skId),
            eq(userId)))
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
  @WithMockUser(roles = "KRT_MEMBER")
  void removeMemberAjax_backendRefuses_relays403() throws Exception {
    stubOfficerReach();
    when(backendApiClient.delete(
            eq("/api/v1/special-commands/{id}/members/{userId}"),
            eq(Void.class),
            eq(skId),
            eq(memberId)))
        .thenThrow(new BackendServiceException("forbidden", null, 403));

    mockMvc
        .perform(
            post("/organisation/special-commands/" + skId + "/members/" + memberId + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void patchMemberFlags_classic_redirectsToMemberPage() throws Exception {
    stubOfficerReach();
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
