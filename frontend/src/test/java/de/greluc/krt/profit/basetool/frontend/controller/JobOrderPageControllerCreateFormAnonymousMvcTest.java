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
import de.greluc.krt.profit.basetool.frontend.model.dto.SystemSettingDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Anonymous-guest behaviour of the Job-Order create form ({@code GET /orders/create}).
 *
 * <p>Covers two things an unauthenticated caller must get right:
 *
 * <ul>
 *   <li>The job-order materials catalog loads through the <em>public</em> WebClient ({@code
 *       isPublic=true}), not the OAuth2-bearer-relaying authenticated one — otherwise the scmdb
 *       shopping-list import finds zero matches (the regression that first motivated this test).
 *   <li>Both owner-pickers populate from the {@code permitAll} {@code /api/v1/org-units/active}
 *       catalog (requesting = all, responsible = the profit-eligible subset incl. SKs), and the
 *       responsible picker is pre-selected to the configured intake Spezialkommando — mirroring the
 *       backend's guest fallback so the form shows the unit the order will land on.
 * </ul>
 */
@SpringBootTest
class JobOrderPageControllerCreateFormAnonymousMvcTest {

  private static final String ACTIVE_URI = "/api/v1/org-units/active";
  private static final String ALL_KINDS_URI = "/api/v1/org-units/active-all-kinds";
  private static final String INTAKE_SETTING_URI =
      "/api/v1/settings/job_order.intake_special_command_id";

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
  void viewCreateForm_AsAnonymousGuest_ShouldFetchMaterialsThroughPublicWebClient()
      throws Exception {
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/orders/create"))
        .andExpect(status().isOk())
        .andExpect(view().name("orders-create"));

    verify(backendApiClient).getCached(eq("/api/v1/materials/job-order"), anyTypeRef(), eq(true));
    verify(backendApiClient, never()).getCached(eq("/api/v1/materials/job-order"), anyTypeRef());
  }

  @Test
  @WithAnonymousUser
  void viewCreateForm_AsAnonymousGuest_PopulatesPickersAndPreselectsIntakeSk() throws Exception {
    UUID intakeId = UUID.randomUUID();
    OrgUnitMembershipOptionDto profitStaffel =
        new OrgUnitMembershipOptionDto(UUID.randomUUID(), "Profit Staffel", "PS", "SQUADRON", true);
    OrgUnitMembershipOptionDto intakeSk =
        new OrgUnitMembershipOptionDto(intakeId, "Intake SK", "INTK", "SPECIAL_COMMAND", true);
    OrgUnitMembershipOptionDto nonProfitSk =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "Combat SK", "CSK", "SPECIAL_COMMAND", false);

    // Reference catalogs (materials / orderable items / squadrons) go through the cached public
    // client; an empty list keeps them from blocking the render.
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());
    // The org-unit catalog and the intake-SK setting are reachable anonymously (permitAll) via the
    // public client.
    when(backendApiClient.get(eq(ACTIVE_URI), anyTypeRef(), eq(true)))
        .thenReturn(List.of(profitStaffel, intakeSk, nonProfitSk));
    when(backendApiClient.get(eq(INTAKE_SETTING_URI), eq(SystemSettingDto.class), eq(true)))
        .thenReturn(new SystemSettingDto(INTAKE_SETTING_URI, intakeId.toString(), 0L));

    mockMvc
        .perform(get("/orders/create"))
        .andExpect(status().isOk())
        .andExpect(view().name("orders-create"))
        // Requesting picker offers every active org unit, including the non-profit SK ...
        .andExpect(content().string(Matchers.containsString("Combat SK")))
        .andExpect(content().string(Matchers.containsString("Profit Staffel")))
        // ... and the responsible picker pre-selects the configured intake SK. Thymeleaf preserves
        // the template's inter-attribute newline, so value and selected are whitespace- (not
        // space-) separated — match with a DOTALL regex.
        .andExpect(
            content()
                .string(
                    Matchers.matchesPattern(
                        Pattern.compile(
                            ".*value=\""
                                + Pattern.quote(intakeId.toString())
                                + "\"\\s+selected=\"selected\".*",
                            Pattern.DOTALL))));

    verify(backendApiClient).get(eq(ACTIVE_URI), anyTypeRef(), eq(true));
    verify(backendApiClient).get(eq(INTAKE_SETTING_URI), eq(SystemSettingDto.class), eq(true));
    // A guest keeps the Staffel/SK-only catalog — the Bereich/OL tiers (authenticated, epic #692)
    // are never offered to an anonymous caller.
    verify(backendApiClient, never()).get(eq(ALL_KINDS_URI), anyTypeRef());
  }
}
