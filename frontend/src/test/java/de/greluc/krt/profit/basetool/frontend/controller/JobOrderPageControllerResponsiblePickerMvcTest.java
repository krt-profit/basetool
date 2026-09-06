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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
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

  /**
   * REQ-FE-016: the create form's material-line select opts into the server-side-search combobox
   * enhancement — the {@code data-krt-combobox} marker must carry the {@code
   * remote-materials-joborder} source key (anchored via its adjacent {@code data-role}) so the
   * global enhancer wires the job-order material search — and the material catalog must no longer
   * be dumped into the page as a preloaded option list (the blank create row has no preselect, so
   * no catalog material name may render at all).
   */
  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void viewCreateForm_materialPickerCarriesComboboxMarker() throws Exception {
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(eq(CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    // The catalog stays a server-side model attribute (it gates the redisplay seed option), but
    // with no bound row value neither material name may reach the rendered page.
    MaterialDto agricium = jobOrderMaterial("Agricium");
    MaterialDto quantainium = jobOrderMaterial("Quantainium-Distinct");
    when(backendApiClient.getCached(
            eq(CachedCatalog.MATERIALS_JOB_ORDER), anyTypeRef()))
        .thenReturn(List.of(agricium, quantainium));

    mockMvc
        .perform(get("/orders/create"))
        .andExpect(status().isOk())
        .andExpect(view().name("orders-create"))
        .andExpect(
            content()
                .string(
                    Matchers.containsString(
                        "data-role=\"material-select\""
                            + " data-krt-combobox=\"remote-materials-joborder\"")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("Quantainium-Distinct"))));
  }

  /**
   * Builds a minimal visible, job-order-eligible SCU material for the catalog stub — only the
   * fields the create form's picker gating reads are populated.
   *
   * @param name the material name the catalog entry carries
   * @return the stub catalog entry
   */
  private static MaterialDto jobOrderMaterial(String name) {
    return new MaterialDto(
        UUID.randomUUID(),
        name,
        null,
        "SCU",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Boolean.TRUE,
        null,
        Boolean.TRUE,
        0L);
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
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    // Authenticated requesting picker sources the all-kinds catalog via the authenticated client
    // (now cached — REQ-DATA-007, eviction gated on Squadron/SK/Bereich/OL admin mutations).
    when(backendApiClient.getCached(eq(CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS), anyTypeRef()))
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
    verify(backendApiClient).getCached(eq(CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS), anyTypeRef());
    verify(backendApiClient, never())
        .getCached(eq(CachedCatalog.ORG_UNITS_ACTIVE), anyTypeRef());
    verify(backendApiClient, never())
        .getCached(eq(CachedCatalog.SPECIAL_COMMANDS), anyTypeRef());
  }
}
