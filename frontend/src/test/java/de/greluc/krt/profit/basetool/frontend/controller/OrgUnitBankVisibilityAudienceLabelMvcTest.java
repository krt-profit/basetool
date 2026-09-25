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
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountSettingsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.jetbrains.annotations.Nullable;
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
 * MVC render test for the {@code ALL_MEMBERS} visibility label on the org-unit account drill-in
 * (REQ-BANK-035/-041): "Alle Mitglieder der Org-Einheit" on {@code ORG_UNIT} and {@code AREA}
 * accounts, "Alle Mitglieder" on a {@code SPECIAL} account.
 */
@SpringBootTest
class OrgUnitBankVisibilityAudienceLabelMvcTest {

  /** The label element the two cases disagree about, matched with its rendered text. */
  private static final String LABEL = "data-testid=\"org-unit-vis-all-members-label\">";

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
   * Stubs the account drill-in and its settings for an account of the given type, with visibility
   * settings open to the caller and empty, non-editable approval limits.
   *
   * @param accountId the account id used in every backend URI
   * @param accountType the account-type enum name ({@code ORG_UNIT} / {@code AREA} / {@code
   *     SPECIAL})
   * @param orgUnitKind the owning org-unit kind enum name, or {@code null} for a Sonderkonto
   */
  private void stubSettings(UUID accountId, String accountType, @Nullable String orgUnitKind) {
    BankAccountDto account =
        new BankAccountDto(
            accountId,
            "KB-0001",
            "Konto KB-0001",
            accountType,
            "ACTIVE",
            null,
            null,
            new BigDecimal("1850000"),
            null,
            null,
            null,
            3L,
            Instant.parse("2026-01-01T00:00:00Z"));
    BankApprovalLimitsDto noLimits =
        new BankApprovalLimitsDto(
            false, false, false, false, List.of(), Map.of(), null, null, List.of());
    BankAccountDetailDto inner =
        new BankAccountDetailDto(
            account,
            new BigDecimal("420000"),
            128L,
            new BankCapabilitiesDto(false, false, false, false),
            noLimits);
    OrgUnitBankAccountDetailDto detail =
        new OrgUnitBankAccountDetailDto(inner, true, false, true, false, false, null, false);
    OrgUnitBankAccountSettingsDto settings =
        new OrgUnitBankAccountSettingsDto(
            accountId,
            "KB-0001",
            "Konto KB-0001",
            accountType,
            orgUnitKind,
            null,
            3L,
            false,
            true,
            true,
            true,
            false,
            "SPECIAL".equals(accountType),
            List.of(),
            List.of(),
            false,
            false,
            List.of(),
            false,
            noLimits);

    String detailUri = "/api/v1/org-units/bank/accounts/" + accountId;
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(detailUri), eq(OrgUnitBankAccountDetailDto.class)))
        .thenReturn(detail);
    when(backendApiClient.get(eq(detailUri + "/settings"), eq(OrgUnitBankAccountSettingsDto.class)))
        .thenReturn(settings);
    when(backendApiClient.get(contains(detailUri + "/transactions"), anyTypeRef()))
        .thenReturn(new PageResponse<BankBookingDto>(List.of(), 0, 20, 0L, 0, List.of()));
  }

  /**
   * On a Staffel account the all-members visibility bucket only admits members of the owning org
   * unit, so its label names that scope — matching the approval-limit tier.
   *
   * @throws Exception when the MockMvc exchange fails
   */
  @Test
  @WithMockUser(roles = {"OFFICER"})
  void visibilitySettings_orgUnitAccount_labelsAllMembersWithTheOrgUnitScope() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubSettings(accountId, "ORG_UNIT", "SQUADRON");

    mockMvc
        .perform(get("/org-unit-bank/accounts/" + accountId))
        .andExpect(status().isOk())
        .andExpect(
            content().string(Matchers.containsString(LABEL + "Alle Mitglieder der Org-Einheit<")))
        .andExpect(
            content().string(Matchers.not(Matchers.containsString(LABEL + "Alle Mitglieder<"))));
  }

  /**
   * On a Sonderkonto the very same bucket admits every KRT member — there is no owning org unit —
   * so the label must stay the org-wide wording rather than inherit the org-unit scope.
   *
   * @throws Exception when the MockMvc exchange fails
   */
  @Test
  @WithMockUser(roles = {"OFFICER"})
  void visibilitySettings_specialAccount_keepsTheOrgWideAllMembersLabel() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubSettings(accountId, "SPECIAL", null);

    mockMvc
        .perform(get("/org-unit-bank/accounts/" + accountId))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString(LABEL + "Alle Mitglieder<")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("??bank.orgUnit"))));
  }
}
