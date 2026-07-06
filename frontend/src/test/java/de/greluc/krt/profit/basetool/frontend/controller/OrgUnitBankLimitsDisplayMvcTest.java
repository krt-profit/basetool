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
 * Regression MVC render test for the read-only approval-limit display on the org-unit account
 * drill-in ({@code org-unit-bank-account-detail.html}, REQ-BANK-041) against a Thymeleaf
 * attribute-precedence bug.
 *
 * <p>The read-only {@code limitsDisplay} block ({@code fragments/bank-approval-limits.html},
 * rendered as {@code data-testid="bank-approval-limits-display"} when the limits are non-empty and
 * not editable) is meant for the plain viewer only — a manager sees the editor in the settings tab
 * instead. The bug put {@code th:if="${settings == null}"} on the <em>same</em> element as {@code
 * th:replace}; because {@code th:replace} (attribute precedence 1) runs before {@code th:if} (3),
 * the guard was dead and the display rendered for the manager too, duplicating the editor. Both
 * tests supply a non-empty, non-editable {@code detail.detail().approvalLimits()} payload and only
 * flip whether {@code settings} is null; a pure view-name unit test would not catch the resurrected
 * duplicate box.
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
   * Builds the read-only approval-limits payload that feeds {@code
   * detail.detail().approvalLimits()} and drives the {@code limitsDisplay} fragment: non-editable
   * ({@code canEdit=false}, invariant on this read-only surface) with a single all-members ceiling
   * so {@code hasAny()} is {@code true} and the display block would render whenever its outer
   * {@code settings == null} guard lets it.
   *
   * @return an approval-limits DTO with {@code hasAny()==true} and {@code canEdit()==false}
   */
  private static BankApprovalLimitsDto readOnlyNonEmptyLimits() {
    return new BankApprovalLimitsDto(
        false, true, true, false, List.of(), Map.of(), new BigDecimal("500000"), null, List.of());
  }

  /**
   * Builds the ORG_UNIT account-detail payload for the drill-in, carrying the given read-only
   * approval limits and enough facts (balance, target, 30-day delta, booking count) for the page to
   * render its KPI tiles without a Thymeleaf dereference error.
   *
   * @param accountId the account id
   * @param canManage whether the caller may manage the account, which flips the controller's {@code
   *     settings != null} branch — mapped onto {@code canSetTarget} here
   * @return the org-unit account-detail DTO wrapping the shared detail
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
    // canManage drives settings != null; expose it via canSetTarget (canConfigureVisibility /
    // canConfigureApprovalLimits stay false so the manager case needs no /users/lookup stub).
    return new OrgUnitBankAccountDetailDto(inner, true, canManage, false, true, false, null);
  }

  /**
   * Stubs the account-detail and booking-history reads the drill-in makes, and — when {@code
   * canManage} — the {@code /settings} read that populates the non-null {@code settings} model
   * attribute driving the settings tab.
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
              // The settings-tab editor is gated by settings.approvalLimits().canEdit(); keep it
              // false so the (absent) editor tile stays out and only the settings != null branch is
              // under test.
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
    // canManage -> settings != null. The read-only limits display must NOT render: the manager gets
    // the editor in the settings tab instead. Pre-fix the th:if/th:replace precedence bug let it
    // render anyway, duplicating the editor below the KPIs.
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
    // Not a manager -> settings == null. The read-only limits display DOES render (positive
    // control): a plain viewer still sees the limits that apply to their own requests.
    UUID accountId = UUID.randomUUID();
    stubDetail(accountId, false);

    mockMvc
        .perform(get("/org-unit-bank/accounts/" + accountId))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(Matchers.containsString("data-testid=\"bank-approval-limits-display\"")));
  }
}
