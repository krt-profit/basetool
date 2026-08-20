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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankApprovalLimitsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankCapabilitiesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * MVC render test for the read-only approval-limit display on the bank-staff account view ({@code
 * bank-account-detail.html}, REQ-BANK-041).
 *
 * <p>Two properties are pinned, both of which decide whether a <strong>plain
 * Bankmitarbeiter</strong> ever gets to see the ceilings they book against:
 *
 * <ol>
 *   <li><b>Role-blind.</b> The backend assembles this surface's limits with {@code canEdit=false}
 *       for every viewer, so the display must render for {@code BANK_EMPLOYEE} exactly as it does
 *       for {@code BANK_MANAGEMENT} — there is no management-only gate on this box.
 *   <li><b>Outside the collapsed Konto-Info tile.</b> The box used to live inside the {@code
 *       #bank-info-body} panel, which the server renders {@code hidden} and which re-collapses on
 *       every {@code accountBody} swap. It now sits above that tile so a configured limit is
 *       visible without expanding anything.
 * </ol>
 */
@SpringBootTest
class BankAccountDetailLimitsMvcTest {

  /**
   * Marker of the read-only limits box rendered by {@code bank-approval-limits :: limitsDisplay}.
   */
  private static final String LIMITS_BOX = "data-testid=\"bank-approval-limits-display\"";

  /** Marker of the collapsible Konto-Info tile the limits box must no longer be nested in. */
  private static final String INFO_PANEL = "data-testid=\"bank-info-panel\"";

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  /** Builds the MockMvc instance with the Spring Security filter chain applied. */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Stubs the bank-staff account detail of an ORG_UNIT account carrying one role-bucket ceiling and
   * one all-members ceiling, assembled read-only ({@code canEdit=false}) exactly as {@code
   * BankAccountService#getAccountDetail} does for every caller of this surface.
   *
   * @param accountId the account id used in every backend URI
   */
  private void stubDetail(UUID accountId) {
    BankAccountDto account =
        new BankAccountDto(
            accountId,
            "KB-0001",
            "Staffel IRIDIUM",
            "ORG_UNIT",
            "ACTIVE",
            null,
            null,
            new BigDecimal("1850000"),
            null,
            null,
            null,
            0L,
            Instant.parse("2026-01-15T10:00:00Z"));
    BankApprovalLimitsDto limits =
        new BankApprovalLimitsDto(
            false,
            true,
            true,
            false,
            List.of("KOMMANDOLEITER"),
            Map.of("KOMMANDOLEITER", new BigDecimal("1000000")),
            new BigDecimal("500000"),
            null,
            List.of());
    BankAccountDetailDto detail =
        new BankAccountDetailDto(
            account,
            new BigDecimal("420000"),
            128L,
            new BankCapabilitiesDto(true, true, true, false),
            limits);

    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(
            eq("/api/v1/bank/accounts/" + accountId), eq(BankAccountDetailDto.class)))
        .thenReturn(detail);
    when(backendApiClient.get(contains("/transactions"), anyTypeRef()))
        .thenReturn(new PageResponse<BankBookingDto>(List.of(), 0, 20, 0L, 0, List.of()));
  }

  /**
   * A plain bank employee sees the configured ceilings, and sees them without expanding the
   * default-collapsed Konto-Info tile.
   *
   * @throws Exception when the MockMvc exchange fails
   */
  @Test
  @WithMockUser(roles = {"BANK_EMPLOYEE"})
  void accountDetail_asBankEmployee_showsLimitsOutsideTheCollapsedInfoTile() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubDetail(accountId);

    String html =
        mockMvc
            .perform(get("/bank/accounts/" + accountId))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString(LIMITS_BOX)))
            // The tier labels resolve (a missing bundle entry would render as ??key_de??).
            .andExpect(content().string(Matchers.containsString("Alle Mitglieder der Org-Einheit")))
            .andReturn()
            .getResponse()
            .getContentAsString();

    int limitsAt = html.indexOf(LIMITS_BOX);
    int infoPanelAt = html.indexOf(INFO_PANEL);
    assertTrue(infoPanelAt > 0, "the Konto-Info tile should still render");
    assertTrue(
        limitsAt < infoPanelAt,
        "the read-only limits box must render ABOVE the collapsed Konto-Info tile, not inside it");
  }

  /**
   * Bank management sees the very same read-only box — the surface never offers the editor, so a
   * regression that gates the box on the management role would be invisible to a one-role test.
   *
   * @throws Exception when the MockMvc exchange fails
   */
  @Test
  @WithMockUser(roles = {"BANK_MANAGEMENT"})
  void accountDetail_asBankManagement_showsTheSameReadOnlyLimits() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubDetail(accountId);

    mockMvc
        .perform(get("/bank/accounts/" + accountId))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString(LIMITS_BOX)))
        // Read-only on this surface: no set/clear editor, whoever is looking (REQ-BANK-041).
        .andExpect(
            content()
                .string(
                    Matchers.not(
                        Matchers.containsString("data-testid=\"bank-approval-limit-settings\""))));
  }

  /**
   * The box stays part of the {@code accountBody} swap, so a peer's limit write refreshes it in
   * place instead of leaving a stale ceiling on screen (REQ-FE-005).
   *
   * @throws Exception when the MockMvc exchange fails
   */
  @Test
  @WithMockUser(roles = {"BANK_EMPLOYEE"})
  void accountDetail_accountBodyFragment_stillCarriesTheLimits() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubDetail(accountId);

    mockMvc
        .perform(get("/bank/accounts/" + accountId).param("fragment", "accountBody"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString(LIMITS_BOX)));
  }
}
