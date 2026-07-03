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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
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
 * Owner-picker sourcing for the Job-Order create form, for an <em>authenticated</em> caller. Since
 * epic #692 the requesting (Auftraggeber) picker offers all four kinds — including Bereiche and the
 * Organisationsleitung — sourced from the authenticated {@code GET
 * /api/v1/org-units/active-all-kinds} catalog, so a Bereichsleitung/OL member can place an order on
 * behalf of their tier. The responsible picker is the {@code isProfitEligible} subset, which keeps
 * a profit-eligible SK and excludes the (never-profit) Bereich/OL — they can be the customer but
 * never the processor. This test pins that an eligible SK still reaches the responsible picker,
 * that a Bereich + OL reach the requesting picker (they are non-profit, so a rendered Bereich/OL
 * name can only have come from the requesting picker), that the all-kinds catalog is the source,
 * and that the deprecated SK-catalog call is gone. The anonymous-guest path (which keeps the
 * Staffel/SK-only {@code /active} catalog) is covered by {@link
 * JobOrderPageControllerCreateFormAnonymousMvcTest}.
 */
@SpringBootTest
class JobOrderPageControllerResponsiblePickerMvcTest {

  private static final String ACTIVE_URI = "/api/v1/org-units/active";
  private static final String ALL_KINDS_URI = "/api/v1/org-units/active-all-kinds";
  private static final String SK_CATALOG_URI = "/api/v1/special-commands?size=1000&sort=name,asc";

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void viewCreateForm_authenticated_requestingOffersBereichAndOl_responsibleStaysProfitStaffelSk()
      throws Exception {
    OrgUnitMembershipOptionDto profitStaffel =
        new OrgUnitMembershipOptionDto(UUID.randomUUID(), "Test Staffel", "TS", "SQUADRON", true);
    OrgUnitMembershipOptionDto profitSk =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "Profit Spezialkommando", "PSK", "SPECIAL_COMMAND", true);
    OrgUnitMembershipOptionDto bereich =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "Bereich Profit XYZ", "P", "BEREICH", false);
    OrgUnitMembershipOptionDto ol =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "Kartellleitung XYZ", "OL", "ORGANISATIONSLEITUNG", false);

    // Reference catalogs (materials / orderable items / squadrons) go through the cached client;
    // empty keeps them from blocking the render.
    when(backendApiClient.getCached(anyString(), anyTypeRef(), anyBoolean()))
        .thenReturn(Collections.emptyList());
    // Authenticated requesting picker sources the all-kinds catalog via the authenticated client.
    when(backendApiClient.get(eq(ALL_KINDS_URI), anyTypeRef()))
        .thenReturn(List.of(profitStaffel, profitSk, bereich, ol));

    mockMvc
        .perform(get("/orders/create"))
        .andExpect(status().isOk())
        .andExpect(view().name("orders-create"))
        // The profit SK still reaches the responsible picker.
        .andExpect(content().string(Matchers.containsString("Profit Spezialkommando")))
        // The Bereich + OL are non-profit, so a rendered Bereich/OL option name can only have come
        // from the requesting picker (the responsible picker filters non-profit out).
        .andExpect(content().string(Matchers.containsString("Bereich Profit XYZ")))
        .andExpect(content().string(Matchers.containsString("Kartellleitung XYZ")));

    // Authenticated callers source the all-kinds catalog — never the Staffel/SK-only /active.
    verify(backendApiClient).get(eq(ALL_KINDS_URI), anyTypeRef());
    verify(backendApiClient, never()).get(eq(ACTIVE_URI), anyTypeRef(), anyBoolean());
    verify(backendApiClient, never()).getCached(eq(SK_CATALOG_URI), anyTypeRef(), anyBoolean());
  }
}
