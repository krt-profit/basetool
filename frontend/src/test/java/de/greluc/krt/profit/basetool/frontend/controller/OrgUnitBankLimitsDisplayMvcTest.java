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
 * MVC render test for the read-only approval-limit display on the org-unit account drill-in
 * (REQ-BANK-041): it renders for a plain viewer but not for a manager, who sees the editor instead.
 */
@SpringBootTest
class OrgUnitBankLimitsDisplayMvcTest {

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
   * Builds a non-editable approval-limits payload with a single all-members ceiling, so {@code
   * hasAny()} is {@code true}.
   *
   * @return an approval-limits DTO with {@code hasAny()==true} and {@code canEdit()==false}
   */
  private static BankApprovalLimitsDto readOnlyNonEmptyLimits() {
    return new BankApprovalLimitsDto(
        false, true, true, false, List.of(), Map.of(), new BigDecimal("500000"), null, List.of());
  }

  /**
   * Builds the ORG_UNIT account-detail payload with read-only approval limits and the facts the KPI
   * tiles need.
   *
   * @param accountId the account id
   * @param canManage whether the caller may manage the account, mapped onto {@code canSetTarget}
   * @return the org-unit account-detail DTO
   */
  private static OrgUnitBankAccountDetailDto detailDto(UUID accountId, boolean canManage) {
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
            new BigDecimal("2000000"),
            null,
            null,
            3L,
            Instant.parse("2026-01-01T00:00:00Z"));
    BankAccountDetailDto inner =
        new BankAccountDetailDto(
            account,
            new BigDecimal("420000"),
            128L,
            new BankCapabilitiesDto(false, false, false, false),
            readOnlyNonEmptyLimits());
    return new OrgUnitBankAccountDetailDto(inner, true, canManage, false, true, false, null, false);
  }

  /**
   * Stubs the account-detail and booking-history reads and, when {@code canManage}, the {@code
   * /settings} read that makes the {@code settings} model attribute non-null.
   *
   * @param accountId the account id used in every backend URI
   * @param canManage whether to also stub the manager-only {@code /settings} read
   */
  private void stubDetail(UUID accountId, boolean canManage) {
    OrgUnitBankAccountDetailDto detail = detailDto(accountId, canManage);
    BankBookingDto booking =
        new BankBookingDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "DEPOSIT",
            new BigDecimal("250000"),
            "someHolder",
            "Missionsertrag",
            null,
            null,
            Instant.parse("2026-06-10T18:30:00Z"),
            null,
            null,
            null,
            null,
            false,
            BigDecimal.ZERO,
            null,
            null);
    PageResponse<BankBookingDto> bookings =
        new PageResponse<>(List.of(booking), 0, 20, 1L, 1, List.of());

    String detailUri = "/api/v1/org-units/bank/accounts/" + accountId;
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(detailUri), eq(OrgUnitBankAccountDetailDto.class)))
        .thenReturn(detail);
    when(backendApiClient.get(eq(detailUri + "/transactions?page=0"), anyTypeRef()))
        .thenReturn(bookings);
    if (canManage) {
      OrgUnitBankAccountSettingsDto settings =
          new OrgUnitBankAccountSettingsDto(
              accountId,
              "KB-0001",
              "Staffel IRIDIUM",
              "ORG_UNIT",
              "SQUADRON",
              new BigDecimal("2000000"),
              3L,
              true,
              false,
              false,
              false,
              false,
              false,
              List.of(),
              List.of(),
              false,
              false,
              List.of(),
              false,
              new BankApprovalLimitsDto(
                  false, false, false, false, List.of(), Map.of(), null, null, List.of()));
      when(backendApiClient.get(
              eq(detailUri + "/settings"), eq(OrgUnitBankAccountSettingsDto.class)))
          .thenReturn(settings);
    }
  }

  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_managerView_omitsReadOnlyLimitsDisplay() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubDetail(accountId, true);

    mockMvc
        .perform(get("/org-unit-bank/accounts/" + accountId))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    Matchers.not(
                        Matchers.containsString("data-testid=\"bank-approval-limits-display\""))));
  }

  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_plainViewer_showsReadOnlyLimitsDisplay() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubDetail(accountId, false);

    mockMvc
        .perform(get("/org-unit-bank/accounts/" + accountId))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(Matchers.containsString("data-testid=\"bank-approval-limits-display\"")));
  }

  /**
   * Verifies that the all-members limit row is labelled "Alle Mitglieder der Org-Einheit" from its
   * own {@code bank.approvalLimit.tier.allMembers} key, not the visibility key (REQ-BANK-041).
   *
   * @throws Exception if the MockMvc exchange fails
   */
  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_limitsDisplay_labelsAllMembersTierWithItsOrgUnitScope() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubDetail(accountId, false);

    mockMvc
        .perform(get("/org-unit-bank/accounts/" + accountId))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString(">Alle Mitglieder der Org-Einheit<")))
        .andExpect(content().string(Matchers.not(Matchers.containsString(">Alle Mitglieder<"))))
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("??bank.approvalLimit.tier"))));
  }
}
