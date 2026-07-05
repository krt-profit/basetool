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
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Renders the bank dashboard ({@code /bank}) to pin the client-side account-name live filter
 * (REQ-BANK-046): the search box wiring ({@code data-bank-acc-filter} + its scope/empty selectors),
 * the per-card {@code data-filter-name} the filter matches against, and the filter-empty note. Also
 * pins the two view-option checkboxes (table view + by-Bereich grouping, REQ-BANK-016), the header
 * direct-booking Kontobewegung CTA + modal (REQ-BANK-023), that the filter is available to a plain
 * {@code BANK_EMPLOYEE} while the three-month report stays {@code BANK_MANAGEMENT}-only (the
 * Verwaltung / Berechtigungen links moved to the sidebar), and that the filter is omitted entirely
 * when the caller has no cards to filter.
 */
@SpringBootTest
class BankDashboardFilterMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static BankDashboardAccountDto account(String accountNo, String name) {
    return new BankDashboardAccountDto(
        UUID.randomUUID(),
        accountNo,
        name,
        "ORG_UNIT",
        "ACTIVE",
        new BigDecimal("1000"),
        BigDecimal.ZERO,
        List.of(),
        null,
        null,
        null);
  }

  /** One active account, so the dashboard's direct-booking Kontobewegung CTA + modal render. */
  private static BankAccountDto activeAccount() {
    return new BankAccountDto(
        UUID.randomUUID(),
        "KB-0001",
        "Staffel IRIDIUM",
        "ORG_UNIT",
        "ACTIVE",
        null,
        null,
        new BigDecimal("1000"),
        null,
        null,
        null,
        0L,
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  @WithMockUser(roles = "BANK_MANAGEMENT")
  void dashboard_managementUser_rendersFilterViewCheckboxesAndMovementCta() throws Exception {
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(
            new BankDashboardDto(
                true,
                List.of(account("KB-0001", "Staffel IRIDIUM"), account("KB-0002", "KRT")),
                null));
    // One active account makes the direct-booking Kontobewegung CTA + modal render (canBook true).
    when(backendApiClient.get(startsWith("/api/v1/bank/accounts"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(activeAccount()), 0, 500, 1, 1, List.of()));

    mockMvc
        .perform(get("/bank"))
        .andExpect(status().isOk())
        // The live-filter search box + its scope/empty wiring render.
        .andExpect(content().string(Matchers.containsString("id=\"bank-acc-filter\"")))
        .andExpect(content().string(Matchers.containsString("data-bank-acc-filter")))
        // The table-view + by-Bereich grouping checkboxes render for every viewer with cards
        // (marker attributes on <input type=checkbox>, not filled toggle buttons).
        .andExpect(content().string(Matchers.containsString("data-bank-view-toggles")))
        .andExpect(content().string(Matchers.containsString("data-bank-view-layout")))
        .andExpect(content().string(Matchers.containsString("data-bank-view-group")))
        // The filter now scopes the whole switchable grid so it works across every view.
        .andExpect(
            content().string(Matchers.containsString("data-filter-scope=\"#bank-grid-results\"")))
        .andExpect(
            content()
                .string(Matchers.containsString("data-filter-empty=\"#bank-acc-filter-empty\"")))
        // Each card carries the account name the filter matches against.
        .andExpect(
            content().string(Matchers.containsString("data-filter-name=\"Staffel IRIDIUM\"")))
        // The no-results note is present (hidden until the filter empties the grid); the direct-
        // booking Kontobewegung CTA + modal render, and the management-only three-month report. The
        // Verwaltung / Berechtigungen links are gone from the header (they live in the sidebar).
        .andExpect(content().string(Matchers.containsString("id=\"bank-acc-filter-empty\"")))
        .andExpect(content().string(Matchers.containsString("bank-movement-open")))
        .andExpect(content().string(Matchers.containsString("id=\"bank-movement-modal\"")))
        .andExpect(content().string(Matchers.containsString("bank-report-download")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("bank-manage-link"))))
        .andExpect(content().string(Matchers.not(Matchers.containsString("bank-grants-link"))));
  }

  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void dashboard_employeeUser_seesFilterButNotManagementActions() throws Exception {
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(
            new BankDashboardDto(false, List.of(account("KB-0001", "Staffel IRIDIUM")), null));

    mockMvc
        .perform(get("/bank"))
        .andExpect(status().isOk())
        // The filter is available to a plain employee (not behind the BANK_MANAGEMENT gate)...
        .andExpect(content().string(Matchers.containsString("id=\"bank-acc-filter\"")))
        .andExpect(
            content().string(Matchers.containsString("data-filter-name=\"Staffel IRIDIUM\"")))
        // ...while the management-only three-month report stays hidden, and the Verwaltung /
        // Berechtigungen header links are gone entirely (sidebar only).
        .andExpect(content().string(Matchers.not(Matchers.containsString("bank-report-download"))))
        .andExpect(content().string(Matchers.not(Matchers.containsString("bank-manage-link"))));
  }

  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void dashboard_noCards_omitsFilterBox() throws Exception {
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(new BankDashboardDto(false, List.of(), null));

    mockMvc
        .perform(get("/bank"))
        .andExpect(status().isOk())
        // Nothing to filter -> no search box and no filter-empty note; the server-rendered "no
        // accounts at all" empty state stands in instead.
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("id=\"bank-acc-filter\""))))
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("id=\"bank-acc-filter-empty\""))))
        .andExpect(content().string(Matchers.containsString("bank-dashboard-empty")));
  }
}
