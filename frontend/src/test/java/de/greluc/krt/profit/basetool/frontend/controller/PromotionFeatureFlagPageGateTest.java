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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.config.OrgUnitContextAdvice;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.List;
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
 * Verifies that every {@code /promotion/...} URL returns HTTP 403 for a non-admin whose home
 * squadron has the promotion feature flag off, despite satisfying the role check.
 *
 * <p>The gate reads the {@code promotionFeatureEnabled} model attribute from {@link
 * OrgUnitContextAdvice}, which also hides the sidebar group.
 */
@SpringBootTest
class PromotionFeatureFlagPageGateTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private void stubSquadronContext(UUID squadronId, boolean promotionEnabled) {
    SquadronDto squadron =
        new SquadronDto(squadronId, "IRIDIUM", "IRI", null, true, promotionEnabled, false, 0L);
    when(backendApiClient.get(contains("/api/v1/squadrons"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(squadron), 0, 1000, 1, 1, List.of()));
    when(backendApiClient.getCached(eq(CachedCatalog.SQUADRONS), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(squadron), 0, 1000, 1, 1, List.of()));
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.activeOrgUnit(squadronId));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void officerWithFlagOff_overviewIsForbidden() throws Exception {
    stubSquadronContext(UUID.randomUUID(), false);
    mockMvc.perform(get("/promotion/overview")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void officerWithFlagOff_myEvaluationsIsForbidden() throws Exception {
    stubSquadronContext(UUID.randomUUID(), false);
    mockMvc.perform(get("/promotion/my-evaluations")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void officerWithFlagOff_manageIsForbidden() throws Exception {
    stubSquadronContext(UUID.randomUUID(), false);
    mockMvc.perform(get("/promotion/manage")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void officerWithFlagOff_adminTopicsIsForbidden() throws Exception {
    stubSquadronContext(UUID.randomUUID(), false);
    mockMvc.perform(get("/promotion/admin/topics")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void officerWithFlagOff_rankRequirementsIsForbidden() throws Exception {
    stubSquadronContext(UUID.randomUUID(), false);
    mockMvc.perform(get("/promotion/admin/rank-requirements")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPinnedToFlagOffSquadron_overviewIsForbidden() throws Exception {
    UUID pinnedId = UUID.randomUUID();
    stubSquadronContext(pinnedId, false);
    mockMvc
        .perform(get("/promotion/overview").sessionAttr("iridium.activeOrgUnitId", pinnedId))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPinnedToFlagOnSquadron_overviewIsAccessible() throws Exception {
    UUID pinnedId = UUID.randomUUID();
    stubSquadronContext(pinnedId, true);
    mockMvc
        .perform(get("/promotion/overview").sessionAttr("iridium.activeOrgUnitId", pinnedId))
        .andExpect(status().is2xxSuccessful());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void squadronlessNonAdmin_overviewIsForbidden() throws Exception {
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.activeOrgUnit(null));
    mockMvc.perform(get("/promotion/overview")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void adminAllSquadronsMode_overviewIsAccessible() throws Exception {
    stubSquadronContext(UUID.randomUUID(), true);
    mockMvc.perform(get("/promotion/overview")).andExpect(status().is2xxSuccessful());
  }
}
