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
import static org.mockito.ArgumentMatchers.any;
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
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Who may open the Job-Order create form ({@code GET /orders/create}), and what it loads.
 *
 * <p>The form used to be anonymous — the public request form — and this class pinned the guest
 * behaviour: the Staffel/SK-only picker catalogue and the pre-selected intake Spezialkommando. That
 * feature is gone (ADR-0149), so the first thing to pin is the refusal.
 *
 * <p>Two things survive the change and are still worth guarding:
 *
 * <ul>
 *   <li>The job-order materials catalog loads through the <em>public</em> WebClient ({@code
 *       isPublic=true}), not the OAuth2-bearer-relaying authenticated one — otherwise the scmdb
 *       shopping-list import finds zero matches (the regression that first motivated this test).
 *       The endpoint is still {@code permitAll}, so this stays true for a logged-in caller too.
 *   <li>The blanket {@code .form-group input} rule keeps its zero-specificity {@code :where()}
 *       exclusion, which is a CSS invariant and has nothing to do with who is looking.
 * </ul>
 */
@SpringBootTest
class JobOrderPageControllerCreateFormAuthMvcTest {

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
  @WithAnonymousUser
  void viewCreateForm_AsAnonymousGuest_IsRefused() throws Exception {
    // The whole point of ADR-0149: an order is raised by somebody, and the form says so before it
    // renders. A redirect, not a 403 — the frontend sends a browser to the OAuth2 login.
    mockMvc.perform(get("/orders/create")).andExpect(status().is3xxRedirection());

    // Nothing is fetched at all: the redirect happens before the handler runs, so the catalogue
    // read never starts. This used to assert "never through the public client", which was the
    // weaker half of the same statement.
    verify(backendApiClient, never()).getCached(any(CachedCatalog.class), anyTypeRef());
  }

  @Test
  @WithMockUser
  void viewCreateForm_ShouldFetchMaterialsThroughTheMembersBearer() throws Exception {
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/orders/create"))
        .andExpect(status().isOk())
        .andExpect(view().name("orders-create"));

    // One client, carrying the member's bearer (REQ-SEC-052). The pair of assertions that stood
    // here — fetched through the public client, never through the authenticated one — described a
    // choice that no longer exists.
    verify(backendApiClient).getCached(eq(CachedCatalog.MATERIALS_JOB_ORDER), anyTypeRef());
  }

  // The Material <-> Item order-kind radios must keep the global 1.2rem KRT circle styling: the
  // page's blanket `.form-group input` rule excludes radio/checkbox inputs via a zero-specificity
  // :where(), so it can neither inflate them with its 0.75rem padding nor outrank the combobox
  // rule that reserves right padding for the dropdown chevron. Guards the selector text so a
  // revert to the unfiltered blanket rule fails the build.
  @Test
  @WithMockUser
  void viewCreateForm_blanketInputRuleExcludesRadioAndCheckboxControls() throws Exception {
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/orders/create"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        ".form-group input:where(:not([type='checkbox']):not([type='radio']))")));
  }

  @Test
  @WithMockUser
  void viewCreateForm_PopulatesBothPickersFromTheAllKindsCatalogue() throws Exception {
    OrgUnitMembershipOptionDto profitStaffel =
        new OrgUnitMembershipOptionDto(UUID.randomUUID(), "Profit Staffel", "PS", "SQUADRON", true);
    OrgUnitMembershipOptionDto nonProfitSk =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "Combat SK", "CSK", "SPECIAL_COMMAND", false);

    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(eq(CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS), anyTypeRef()))
        .thenReturn(List.of(profitStaffel, nonProfitSk));

    mockMvc
        .perform(get("/orders/create"))
        .andExpect(status().isOk())
        .andExpect(view().name("orders-create"))
        // The customer picker offers every active unit, the non-profit SK included ...
        .andExpect(content().string(Matchers.containsString("Combat SK")))
        .andExpect(content().string(Matchers.containsString("Profit Staffel")));

    // ... and it comes from the all-kinds catalogue, which carries the Bereich/OL tiers (epic
    // #692). Before ADR-0149 an anonymous caller got the narrower Staffel/SK-only list instead;
    // there is no anonymous caller left, so there is no second list to fall to except on failure.
    verify(backendApiClient).getCached(eq(CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS), anyTypeRef());
    verify(backendApiClient, never()).getCached(eq(CachedCatalog.ORG_UNITS_ACTIVE), anyTypeRef());
  }
}
