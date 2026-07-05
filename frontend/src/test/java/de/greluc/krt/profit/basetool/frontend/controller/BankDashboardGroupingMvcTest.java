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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
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
 * Pins the two switchable-view invariants of the bank dashboard grid (REQ-BANK-016) against a
 * Thymeleaf attribute-precedence regression that shipped in the card/table + by-Bereich feature:
 * {@code th:if}/{@code th:unless}/{@code th:each} must never share an element with {@code
 * th:replace}, because {@code th:replace} (precedence 1) is processed before the condition (3) and
 * the iteration (2).
 *
 * <p>The two failure modes this guards are exactly what a same-element combination produced: the
 * layout condition was ignored so the {@code accTable} and {@code accGrid} fragments BOTH rendered
 * (table and card grid stacked), and the by-Bereich {@code th:each} left its {@code grp} loop
 * variable unbound so {@code accGroup(null, …)} hit a {@code group.key()} NPE that surfaced as HTTP
 * 500 on the {@code bankGrid} fragment swap — silently breaking every view-toggle. The tests assert
 * one-and-only-one view per layout and a clean grouped render, for both the full page and the
 * {@code fragment=bankGrid} swap the checkboxes fire.
 */
@SpringBootTest
class BankDashboardGroupingMvcTest {

  private static final String CARD = "data-testid=\"bank-account-card\"";
  private static final String TABLE = "data-testid=\"bank-account-table\"";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Builds one dashboard card payload with optional Bereich grouping metadata.
   *
   * @param accountNo the display account number
   * @param name the display name
   * @param type the account type enum name (drives the KRT / Sonderkonten buckets)
   * @param bereichId the owning Bereich id, or {@code null} for a non-Bereich account
   * @param bereichName the owning Bereich display name, or {@code null}
   * @param department the Bereich department enum name (group colour), or {@code null}
   * @return the card DTO
   */
  private static BankDashboardAccountDto account(
      String accountNo,
      String name,
      String type,
      UUID bereichId,
      String bereichName,
      String department) {
    return new BankDashboardAccountDto(
        UUID.randomUUID(),
        accountNo,
        name,
        type,
        "ACTIVE",
        new BigDecimal("1000"),
        BigDecimal.ZERO,
        List.of(),
        bereichId,
        bereichName,
        department);
  }

  /**
   * A dashboard with a Bereich-owned Staffel account plus the CARTEL account (its own KRT group).
   */
  private void twoGroupsDashboard() {
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(
            new BankDashboardDto(
                true,
                List.of(
                    account(
                        "KB-0010",
                        "Staffel IRIDIUM",
                        "ORG_UNIT",
                        UUID.randomUUID(),
                        "Bereich Forschung",
                        "RESEARCH"),
                    account("KB-0003", "KRT", "CARTEL", null, null, null)),
                null));
  }

  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void groupByBereich_fragment_rendersGroupsWithoutServerError() throws Exception {
    twoGroupsDashboard();

    // The bankGrid fragment swap the by-Bereich checkbox fires MUST render (regression: grp unbound
    // -> group.key() NPE -> 500). It emits one coloured group section per bucket, keyed by
    // group.key.
    mockMvc
        .perform(get("/bank").param("group", "bereich").param("fragment", "bankGrid"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("data-testid=\"bank-acc-group\"")))
        .andExpect(content().string(Matchers.containsString("data-bank-acc-group=\"krt\"")))
        .andExpect(content().string(Matchers.containsString("data-bank-acc-group=\"bereich:")))
        .andExpect(content().string(Matchers.containsString("Bereich Forschung")));
  }

  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void groupByBereich_fullPage_rendersGroupsWithoutServerError() throws Exception {
    twoGroupsDashboard();

    mockMvc
        .perform(get("/bank").param("group", "bereich"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("data-bank-acc-group=\"krt\"")))
        .andExpect(content().string(Matchers.containsString("data-bank-acc-group=\"bereich:")));
  }

  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void cardLayout_fragment_rendersGridAndNotTable() throws Exception {
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(
            new BankDashboardDto(
                false,
                List.of(account("KB-0001", "Staffel IRIDIUM", "ORG_UNIT", null, null, null)),
                null));

    // Card layout renders the grid ONLY — the table fragment must not also appear (regression: the
    // ignored th:unless rendered both accTable and accGrid, stacking a table above the cards).
    mockMvc
        .perform(
            get("/bank")
                .param("layout", "card")
                .param("group", "alpha")
                .param("fragment", "bankGrid"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString(CARD)))
        .andExpect(content().string(Matchers.not(Matchers.containsString(TABLE))));
  }

  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void tableLayout_fragment_rendersTableAndNotGrid() throws Exception {
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(
            new BankDashboardDto(
                false,
                List.of(account("KB-0001", "Staffel IRIDIUM", "ORG_UNIT", null, null, null)),
                null));

    mockMvc
        .perform(
            get("/bank")
                .param("layout", "table")
                .param("group", "alpha")
                .param("fragment", "bankGrid"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString(TABLE)))
        .andExpect(content().string(Matchers.not(Matchers.containsString(CARD))));
  }
}
