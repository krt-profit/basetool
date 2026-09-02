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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
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
 * The sidebar org-unit switcher's no-pin row, which says two different things to two callers.
 *
 * <p><strong>The row is one control with two meanings.</strong> Choosing it sends no {@code
 * X-Active-Org-Unit-Id}, and {@code RequestScopeResolver#currentScopePredicate} answers that with
 * {@code adminAllScope} — every org unit — for an admin, and with the union of the caller's own
 * reach for everybody else. Labelling both „Alle Org-Einheiten" promised a member more than it
 * delivers: measured against the test stack on 2026-09-01, a member of two Staffeln read 884.8 SCU
 * under that row while an admin read 1403.4.
 *
 * <p><strong>Why this is an MVC render test and not an advice test.</strong> The fork is a pair of
 * {@code sec:authorize} attributes in {@code fragments/sidebar.html}. A malformed SpEL expression
 * there — and the expression carries a {@code T(...)} type reference and a negation — is not a
 * compile error and no unit test would see it; it surfaces at render time, on every page that draws
 * the sidebar. Rendering the page is the only thing that asserts the expression parses at all.
 *
 * <p>Two options are required for the switcher to render, because the template hides itself for a
 * caller with fewer than two — no choice to offer means no control.
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
    when(backendApiClient.get(eq("/api/v1/me/org-units"), anyTypeRef()))
        .thenReturn(
            List.of(
                new OrgUnitMembershipOptionDto(
                    UUID.randomUUID(), "IRIDIUM", "IRI", "SQUADRON", true),
                new OrgUnitMembershipOptionDto(
                    UUID.randomUUID(), "VANGUARD", "VGD", "SQUADRON", true)));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void switcher_admin_offersEveryOrgUnitAndSaysSo() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Alle Org-Einheiten")))
        // The narrower wording must not also be present: two rows with the same empty value would
        // both post an empty selection, and the reader could not tell which scope they picked.
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
    // An officer reaches LOGISTICIAN and MISSION_MANAGER through the hierarchy but not ADMIN, and
    // their unpinned read is their own reach like any other member's. Pinned as its own case
    // because "anything above a member" is exactly the confusion REQ-SEC-047 was about.
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Alle meine Org-Einheiten")));
  }
}
