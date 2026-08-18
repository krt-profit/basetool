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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountRefDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankCapabilitiesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountSettingsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankViewUserDto;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the org-unit officer/lead bank page (epic #666 F1/F2) to pin that the balance card, the
 * request form modal and the own-request list with a cancel action all render without a Thymeleaf
 * error, that the page is gated to leadership roles (not {@code BANK_EMPLOYEE}), and that the
 * {@code orgUnitBank} fragment view resolves for the in-place swap.
 */
@SpringBootTest
class OrgUnitBankPageControllerMvcTest {

  private static final String BALANCES_URI = "/api/v1/org-units/bank/balances";
  private static final String REQUESTS_URI = "/api/v1/org-units/bank/requests";
  private static final String FOREIGN_REQUESTS_URI = "/api/v1/org-units/bank/requests/foreign";
  private static final String TRANSFER_TARGETS_URI = "/api/v1/org-units/bank/transfer-targets";

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

  private void stubData(UUID orgUnitId) {
    UUID accountId = UUID.randomUUID();
    OrgUnitBankBalanceDto balance =
        new OrgUnitBankBalanceDto(
            accountId,
            "KB-0001",
            "Staffel IRIDIUM",
            "ACTIVE",
            "ORG_UNIT",
            orgUnitId,
            "IRIDIUM",
            "IRI",
            "SQUADRON",
            new BigDecimal("1850000"),
            true,
            new BigDecimal("420000"),
            List.of(new BigDecimal("1430000"), new BigDecimal("1850000")),
            new BigDecimal("2000000"),
            true,
            null,
            false);
    BankBookingRequestDto request =
        new BankBookingRequestDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "KB-0001",
            "Staffel IRIDIUM",
            orgUnitId,
            "IRIDIUM",
            "IRI",
            "DEPOSIT",
            new BigDecimal("5000"),
            "from sale",
            null,
            null,
            "PENDING",
            "officerX",
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.parse("2026-06-17T14:02:00Z"),
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            0L);
    BankAccountRefDto target =
        new BankAccountRefDto(accountId, "KB-0001", "Staffel IRIDIUM", "ORG_UNIT");
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(BALANCES_URI), anyTypeRef())).thenReturn(List.of(balance));
    when(backendApiClient.get(eq(REQUESTS_URI), anyTypeRef())).thenReturn(List.of(request));
    when(backendApiClient.get(eq(TRANSFER_TARGETS_URI), anyTypeRef())).thenReturn(List.of(target));
  }

  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_rendersBalanceCardRequestModalAndOwnRequests() throws Exception {
    UUID orgUnitId = UUID.randomUUID();
    stubData(orgUnitId);

    mockMvc
        .perform(get("/org-unit-bank"))
        .andExpect(status().isOk())
        .andExpect(view().name("org-unit-bank"))
        .andExpect(content().string(Matchers.containsString("Staffel IRIDIUM")))
        // The 30-day trend renders: the sign-colored delta label + the inline SVG sparkline,
        // mirroring the bank dashboard cards (REQ-BANK-016).
        .andExpect(content().string(Matchers.containsString("kpi-delta")))
        .andExpect(content().string(Matchers.containsString("kpi-sparkline")))
        // The single page-level request CTA shows (an active account exists) and opens the modal.
        .andExpect(content().string(Matchers.containsString("org-unit-bank-request-btn")))
        .andExpect(content().string(Matchers.containsString("org-unit-request-modal")))
        // The merged source selector lists the active account as an option marked debitable
        // (data-can-debit="true", since it is the caller's request-capable account), and offers the
        // TRANSFER op (REQ-BANK-039/-042).
        .andExpect(content().string(Matchers.containsString("name=\"sourceAccountId\"")))
        .andExpect(content().string(Matchers.containsString("org-unit-request-account")))
        .andExpect(content().string(Matchers.containsString("data-can-debit=\"true\"")))
        .andExpect(content().string(Matchers.containsString("name=\"type\"")))
        // REQ-BANK-040: the transfer destination select MUST be named targetAccountId to match the
        // CreateBankBookingRequest DTO field — a mismatch silently 400s every transfer request.
        .andExpect(content().string(Matchers.containsString("name=\"targetAccountId\"")))
        // The own-request row renders with a cancel form.
        .andExpect(content().string(Matchers.containsString("org-unit-bank-cancel-btn")));
  }

  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_rendersAccountNameFilterOverKontenList() throws Exception {
    // REQ-BANK-046: the "Konten" list carries a client-side account-name live filter — the search
    // box + its scope/empty wiring, the per-row data-filter-name and the filter-empty note all
    // render inside the swapped fragment above the account list.
    stubData(UUID.randomUUID());

    mockMvc
        .perform(get("/org-unit-bank"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("id=\"ou-acc-filter\"")))
        .andExpect(content().string(Matchers.containsString("data-bank-acc-filter")))
        // The filter scopes the switchable account-list container (card grid OR table), not the
        // former single .ou-list, so it works across both view layouts (REQ-BANK-021/-046).
        .andExpect(
            content().string(Matchers.containsString("data-filter-scope=\"#ou-acc-results\"")))
        .andExpect(
            content().string(Matchers.containsString("data-filter-empty=\"#ou-acc-filter-empty\"")))
        // The account row carries the name the filter matches against.
        .andExpect(
            content().string(Matchers.containsString("data-filter-name=\"Staffel IRIDIUM\"")))
        // The no-results note (hidden until the filter empties the list) is present.
        .andExpect(content().string(Matchers.containsString("id=\"ou-acc-filter-empty\"")));
  }

  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_fragmentViewResolves() throws Exception {
    stubData(UUID.randomUUID());

    mockMvc
        .perform(get("/org-unit-bank").param("fragment", "orgUnitBank"))
        .andExpect(status().isOk())
        .andExpect(view().name("org-unit-bank :: orgUnitBank"));
  }

  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_defaultLayoutRendersCardGridAndViewToggle() throws Exception {
    // REQ-BANK-021: the Konten list defaults to the card grid, with a single per-user
    // "Tabellenansicht" view toggle (no by-Bereich grouping). The toggle uses distinct
    // data-ou-view-* attributes and is unchecked by default (card view).
    stubData(UUID.randomUUID());

    mockMvc
        .perform(get("/org-unit-bank"))
        .andExpect(status().isOk())
        // The switchable account-list container + its server-rendered layout marker.
        .andExpect(content().string(Matchers.containsString("id=\"ou-acc-results\"")))
        .andExpect(content().string(Matchers.containsString("data-ou-layout=\"card\"")))
        // The card grid renders (not the dense table's header strip).
        .andExpect(content().string(Matchers.containsString("ou-acc-grid")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("ou-rowhead"))))
        // The single view toggle, unchecked by default (its own module, not the dashboard's).
        .andExpect(content().string(Matchers.containsString("data-ou-view-toggles")))
        .andExpect(content().string(Matchers.containsString("data-ou-view-layout")));
  }

  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_tableLayoutRendersDenseTable() throws Exception {
    // REQ-BANK-021: layout=table renders the dense table (the former .ou-list) instead of the card
    // grid, and the toggle checkbox is checked.
    stubData(UUID.randomUUID());

    mockMvc
        .perform(get("/org-unit-bank").param("layout", "table"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("data-ou-layout=\"table\"")))
        .andExpect(content().string(Matchers.containsString("ou-rowhead")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("ou-acc-grid"))));
  }

  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_accountsFragmentViewResolves() throws Exception {
    // The view-toggle swap re-renders only the switchable account list (REQ-FE-005).
    stubData(UUID.randomUUID());

    mockMvc
        .perform(get("/org-unit-bank").param("fragment", "orgUnitBankAccounts"))
        .andExpect(status().isOk())
        .andExpect(view().name("org-unit-bank :: orgUnitBankAccounts"))
        .andExpect(content().string(Matchers.containsString("Staffel IRIDIUM")));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void orgUnitBank_memberIsPermitted() throws Exception {
    // REQ-BANK-037: the page is reachable by any KRT member (the cartel account is visible to all,
    // and a member may have been granted access to other accounts); the backend seam scopes the
    // visible accounts. The member sees an empty page here because no data is stubbed.
    mockMvc.perform(get("/org-unit-bank")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = {"GUEST"})
  void orgUnitBank_guestIsForbidden() throws Exception {
    mockMvc.perform(get("/org-unit-bank")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_failingFetchDegradesToEmptyAndStillRenders() throws Exception {
    // F5: the landing-page reads run concurrently and each swallows its own failure -> a single
    // backend hiccup degrades to an empty list instead of 500-ing or blanking the page, and every
    // model attribute is still populated. Here /balances throws but /requests is stubbed.
    BankBookingRequestDto request =
        new BankBookingRequestDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "KB-0001",
            "Staffel IRIDIUM",
            UUID.randomUUID(),
            "IRIDIUM",
            "IRI",
            "DEPOSIT",
            new BigDecimal("5000"),
            "from sale",
            null,
            null,
            "PENDING",
            "officerX",
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.parse("2026-06-17T14:02:00Z"),
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            0L);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(BALANCES_URI), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));
    when(backendApiClient.get(eq(REQUESTS_URI), anyTypeRef())).thenReturn(List.of(request));

    mockMvc
        .perform(get("/org-unit-bank"))
        .andExpect(status().isOk())
        .andExpect(view().name("org-unit-bank"))
        .andExpect(model().attributeExists("balances", "ownRequests", "foreignRequests"))
        .andExpect(model().attributeExists("requestTransferTargets", "anyCanRequest", "sparks"))
        // The failed balances fetch degraded to empty -> no requestable account -> no request CTA.
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("org-unit-bank-request-btn"))));
  }

  @Test
  @WithMockUser(roles = {"LOGISTICIAN"})
  void orgUnitBank_specialAccountIsDepositTargetWithCta() throws Exception {
    // REQ-BANK-042: a special account (Sonderkonto) is not withdrawal/transfer-requestable
    // (canRequest=false), but it IS a valid deposit target — so the page-level request CTA + modal
    // are shown and the account appears as a (non-debitable) deposit option. Pins that the template
    // handles the null org unit.
    UUID specialId = UUID.randomUUID();
    OrgUnitBankBalanceDto special =
        new OrgUnitBankBalanceDto(
            specialId,
            "KB-0042",
            "Event Sonderkonto",
            "ACTIVE",
            "SPECIAL",
            null,
            null,
            null,
            null,
            new BigDecimal("250000"),
            false,
            BigDecimal.ZERO,
            List.of(),
            null,
            false,
            null,
            false);
    BankAccountRefDto target =
        new BankAccountRefDto(specialId, "KB-0042", "Event Sonderkonto", "SPECIAL");
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(BALANCES_URI), anyTypeRef())).thenReturn(List.of(special));
    when(backendApiClient.get(eq(REQUESTS_URI), anyTypeRef())).thenReturn(List.of());
    when(backendApiClient.get(eq(TRANSFER_TARGETS_URI), anyTypeRef())).thenReturn(List.of(target));

    mockMvc
        .perform(get("/org-unit-bank"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Event Sonderkonto")))
        // The account row renders (its testid is unchanged from the former card).
        .andExpect(content().string(Matchers.containsString("org-unit-bank-card")))
        // A deposit is possible against the special account -> the CTA + modal render.
        .andExpect(content().string(Matchers.containsString("org-unit-bank-request-btn")))
        .andExpect(content().string(Matchers.containsString("org-unit-request-modal")))
        // It is offered as a deposit option, marked non-debitable (no withdrawal/transfer from it).
        .andExpect(content().string(Matchers.containsString("data-can-debit=\"false\"")));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void orgUnitBank_noViewableAccountsStillOffersDepositCta() throws Exception {
    // REQ-BANK-042: a member who may view no account can still raise a deposit request, so the CTA
    // +
    // modal render whenever at least one active account exists (here only via transfer-targets).
    BankAccountRefDto target = new BankAccountRefDto(UUID.randomUUID(), "KB-0001", "KRT", "CARTEL");
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(BALANCES_URI), anyTypeRef())).thenReturn(List.of());
    when(backendApiClient.get(eq(REQUESTS_URI), anyTypeRef())).thenReturn(List.of());
    when(backendApiClient.get(eq(TRANSFER_TARGETS_URI), anyTypeRef())).thenReturn(List.of(target));

    mockMvc
        .perform(get("/org-unit-bank"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("org-unit-bank-request-btn")))
        .andExpect(content().string(Matchers.containsString("org-unit-request-modal")));
  }

  /**
   * Stubs the read-only account drill-in (REQ-BANK-038) plus the holder/OL settings region: an
   * ORG_UNIT account with a balance target, a single booking, one granted role bucket and one
   * granted user. The visibility/limit user pickers are server-side search comboboxes (#1193), so
   * no user roster is stubbed here.
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
            new de.greluc.krt.profit.basetool.frontend.model.dto.BankApprovalLimitsDto(
                false,
                false,
                false,
                false,
                java.util.List.of(),
                java.util.Map.of(),
                null,
                null,
                java.util.List.of()));
    OrgUnitBankAccountDetailDto detail =
        new OrgUnitBankAccountDetailDto(inner, true, true, true, true, true, null, false);
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
            true,
            true,
            true,
            false,
            false,
            List.of("LOGISTICIAN", "MISSION_MANAGER"),
            List.of("LOGISTICIAN"),
            false,
            false,
            List.of(new OrgUnitBankViewUserDto(UUID.randomUUID(), "greluc")),
            true,
            new de.greluc.krt.profit.basetool.frontend.model.dto.BankApprovalLimitsDto(
                false,
                false,
                false,
                false,
                java.util.List.of(),
                java.util.Map.of(),
                null,
                null,
                java.util.List.of()));
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
    when(backendApiClient.get(eq(detailUri + "/settings"), eq(OrgUnitBankAccountSettingsDto.class)))
        .thenReturn(settings);
    when(backendApiClient.get(contains(detailUri + "/transactions"), anyTypeRef()))
        .thenReturn(bookings);
  }

  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBankAccount_rendersDetailWithSettingsAndVisibilityToggles() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubDetail(accountId);

    mockMvc
        .perform(get("/org-unit-bank/accounts/" + accountId))
        .andExpect(status().isOk())
        .andExpect(view().name("org-unit-bank-account-detail"))
        .andExpect(content().string(Matchers.containsString("Staffel IRIDIUM")))
        // Facts render as the kpi-total grid, target fact included.
        .andExpect(content().string(Matchers.containsString("ou-facts")))
        .andExpect(content().string(Matchers.containsString("org-unit-bank-detail-target")))
        // Two tabs (REQ-BANK-038): Buchungshistorie + Verantwortung & Sichtbarkeit, with the
        // settings
        // region and the booking history each in their tab panel.
        .andExpect(content().string(Matchers.containsString("org-unit-bank-detail-tab-history")))
        .andExpect(content().string(Matchers.containsString("org-unit-bank-detail-tab-settings")))
        .andExpect(content().string(Matchers.containsString("data-tabpanel=\"settings\"")))
        .andExpect(content().string(Matchers.containsString("data-tabpanel=\"history\"")))
        // Settings region with the quiet per-audience visibility toggles.
        .andExpect(content().string(Matchers.containsString("org-unit-bank-settings")))
        .andExpect(content().string(Matchers.containsString("vis-row")))
        .andExpect(content().string(Matchers.containsString("org-unit-vis-role-LOGISTICIAN")))
        // History panel kept (4-column, Halter-redacted).
        .andExpect(content().string(Matchers.containsString("org-unit-bank-bookings-panel")))
        // The always-"Aktiv" status pill was dropped from the header.
        .andExpect(content().string(Matchers.not(Matchers.containsString("status-pill"))));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void orgUnitBankAccount_plainViewerNoLimits_rendersHistoryWithoutTabs() throws Exception {
    // REQ-BANK-038: a viewer who cannot manage and whose account carries no approval limits has no
    // "Verantwortung & Sichtbarkeit" tab — the booking history renders plainly with its own
    // heading,
    // the shared info tiles still on top.
    UUID accountId = UUID.randomUUID();
    BankAccountDto account =
        new BankAccountDto(
            accountId,
            "KB-0007",
            "KRT",
            "CARTEL",
            "ACTIVE",
            null,
            null,
            new BigDecimal("50000"),
            null,
            null,
            null,
            1L,
            Instant.parse("2026-01-01T00:00:00Z"));
    BankAccountDetailDto inner =
        new BankAccountDetailDto(
            account,
            BigDecimal.ZERO,
            3L,
            new BankCapabilitiesDto(false, false, false, false),
            new de.greluc.krt.profit.basetool.frontend.model.dto.BankApprovalLimitsDto(
                false,
                false,
                false,
                false,
                java.util.List.of(),
                java.util.Map.of(),
                null,
                null,
                java.util.List.of()));
    OrgUnitBankAccountDetailDto detail =
        new OrgUnitBankAccountDetailDto(inner, true, false, false, true, false, null, false);
    String detailUri = "/api/v1/org-units/bank/accounts/" + accountId;
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(detailUri), eq(OrgUnitBankAccountDetailDto.class)))
        .thenReturn(detail);
    when(backendApiClient.get(contains(detailUri + "/transactions"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0, List.of()));

    mockMvc
        .perform(get("/org-unit-bank/accounts/" + accountId))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("ou-facts")))
        .andExpect(content().string(Matchers.containsString("org-unit-bank-bookings-panel")))
        // No settings tab for a plain viewer with no limits — and so no tab nav at all.
        .andExpect(
            content()
                .string(Matchers.not(Matchers.containsString("org-unit-bank-detail-tab-settings"))))
        .andExpect(
            content()
                .string(Matchers.not(Matchers.containsString("org-unit-bank-detail-tab-history"))));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void orgUnitBankAccount_viewerWithLimits_seesInlineLimitsButNoResponsibilityTab()
      throws Exception {
    // REQ-BANK-038/-041: a plain viewer (not the responsible holder) still sees the read-only
    // approval-limit display inline (it applies to their own requests), but NOT the "Verantwortung
    // &
    // Sichtbarkeit" tab — that tab is the responsible holder's alone.
    UUID accountId = UUID.randomUUID();
    BankAccountDto account =
        new BankAccountDto(
            accountId,
            "KB-0003",
            "Staffel IRIDIUM",
            "ORG_UNIT",
            "ACTIVE",
            null,
            null,
            new BigDecimal("100000"),
            null,
            null,
            null,
            1L,
            Instant.parse("2026-01-01T00:00:00Z"));
    BankAccountDetailDto inner =
        new BankAccountDetailDto(
            account,
            BigDecimal.ZERO,
            5L,
            new BankCapabilitiesDto(false, false, false, false),
            new de.greluc.krt.profit.basetool.frontend.model.dto.BankApprovalLimitsDto(
                false,
                true,
                true,
                false,
                java.util.List.of("KOMMANDOLEITER"),
                java.util.Map.of("KOMMANDOLEITER", new BigDecimal("1000000")),
                null,
                null,
                java.util.List.of()));
    OrgUnitBankAccountDetailDto detail =
        new OrgUnitBankAccountDetailDto(
            inner, true, false, false, true, false, new BigDecimal("1000000"), false);
    String detailUri = "/api/v1/org-units/bank/accounts/" + accountId;
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(detailUri), eq(OrgUnitBankAccountDetailDto.class)))
        .thenReturn(detail);
    when(backendApiClient.get(contains(detailUri + "/transactions"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0, List.of()));

    mockMvc
        .perform(get("/org-unit-bank/accounts/" + accountId))
        .andExpect(status().isOk())
        // The read-only limits display renders for the viewer...
        .andExpect(content().string(Matchers.containsString("bank-approval-limits-display")))
        // ...but there is no responsibility tab and no editable settings hud-box (match the exact
        // testid attribute — the always-present swap container id is
        // "org-unit-bank-settings-results").
        .andExpect(
            content()
                .string(Matchers.not(Matchers.containsString("org-unit-bank-detail-tab-settings"))))
        .andExpect(
            content()
                .string(
                    Matchers.not(
                        Matchers.containsString("data-testid=\"org-unit-bank-settings\""))));
  }

  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBankAccount_settingsFragmentViewResolves() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubDetail(accountId);

    mockMvc
        .perform(
            get("/org-unit-bank/accounts/" + accountId).param("fragment", "orgUnitBankSettings"))
        .andExpect(status().isOk())
        .andExpect(view().name("org-unit-bank-account-detail :: orgUnitBankSettings"));
  }

  /**
   * Builds a booking request for either the approval tab or the requester's own list, varying only
   * the fields that decide whether the row is expandable.
   *
   * @param note the requester's note, or {@code null}
   * @param justification the requester's Begruendung, or {@code null}
   * @param staffNote the confirming employee's own note (REQ-BANK-054), or {@code null}
   * @return the request DTO
   */
  private static BankBookingRequestDto bookingRequest(
      String note, String justification, String staffNote) {
    return new BankBookingRequestDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "KB-0001",
        "Staffel IRIDIUM",
        UUID.randomUUID(),
        "IRIDIUM",
        "IRI",
        "WITHDRAWAL",
        new BigDecimal("5000"),
        note,
        justification,
        staffNote,
        "PENDING",
        "officerX",
        null,
        null,
        null,
        null,
        null,
        null,
        Instant.parse("2026-06-17T14:02:00Z"),
        null,
        null,
        true,
        new BigDecimal("1000"),
        "RESPONSIBLE_HOLDER",
        false,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        0L);
  }

  /**
   * REQ-BANK-041/-045: the approval tab's rows expand to reveal Begruendung + Notiz, exactly like
   * the bank-staff queue. The approver decides on the Begruendung, so hiding it here would mean
   * signing off blind on a mandating account.
   */
  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_foreignRequestWithJustificationRendersExpandableDetailRow() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(FOREIGN_REQUESTS_URI), anyTypeRef()))
        .thenReturn(List.of(bookingRequest("Missionsertrag", "Missionsfreigabe", null)));

    mockMvc
        .perform(get("/org-unit-bank"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("org-unit-bank-foreign-expand")))
        .andExpect(content().string(Matchers.containsString("org-unit-bank-foreign-detail")))
        .andExpect(content().string(Matchers.containsString("Missionsfreigabe")))
        .andExpect(content().string(Matchers.containsString("Missionsertrag")))
        // The sub-row must span every column incl. the leading expand cell, or the detail cell
        // silently shrinks the table.
        .andExpect(content().string(Matchers.containsString("colspan=\"8\"")))
        // A shared bank-req-<id> would let this chevron toggle the "Meine Antraege" sub-row of the
        // same request when the responsible holder raised it themselves.
        .andExpect(content().string(Matchers.containsString("ou-foreign-req-")));
  }

  /**
   * REQ-BANK-054: the confirming employee's note reaches the approval tab too, and on its own is
   * enough to make the row expandable — a confirmed request may carry only a staff note.
   */
  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_foreignRequestStaffNoteRendersInTheDetailRow() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(FOREIGN_REQUESTS_URI), anyTypeRef()))
        .thenReturn(List.of(bookingRequest(null, null, "Bar uebergeben, Zeuge greluc")));

    mockMvc
        .perform(get("/org-unit-bank"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("org-unit-bank-foreign-expand")))
        .andExpect(content().string(Matchers.containsString("org-unit-bank-foreign-staff-note")))
        .andExpect(content().string(Matchers.containsString("Bar uebergeben, Zeuge greluc")));
  }

  /**
   * REQ-BANK-022/-045: the requester's own "Meine Antraege" rows expand to reveal the Begruendung
   * and Notiz they filed, the same mechanism as the staff queue and the approval tab. Without it a
   * requester could not read back what they had written on a request they may still cancel.
   *
   * @throws Exception if the MockMvc exchange fails
   */
  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_ownRequestWithJustificationRendersExpandableDetailRow() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(REQUESTS_URI), anyTypeRef()))
        .thenReturn(List.of(bookingRequest("Missionsertrag", "Missionsfreigabe", null)));

    mockMvc
        .perform(get("/org-unit-bank"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("org-unit-bank-request-expand")))
        .andExpect(content().string(Matchers.containsString("org-unit-bank-request-detail")))
        .andExpect(content().string(Matchers.containsString("Missionsfreigabe")))
        .andExpect(content().string(Matchers.containsString("Missionsertrag")))
        // Seven columns here, not the approval tab's eight -- a stale colspan silently shrinks the
        // detail cell.
        .andExpect(content().string(Matchers.containsString("colspan=\"7\"")))
        // Distinct from the approval tab's ou-foreign-req-: a responsible holder who raised the
        // request sees the SAME request in both tables, and a shared id would make one chevron
        // toggle the other table's sub-row.
        .andExpect(content().string(Matchers.containsString("ou-own-req-")));
  }

  /**
   * REQ-BANK-054: the "Notiz Bankmitarbeiter" is bank-internal and must not surface in the
   * requester's own list, even though the same DTO type carries it for the approval tab. The seam
   * blanks it, so a staff note alone must leave the row flat — no chevron, no sub-row, no text.
   *
   * @throws Exception if the MockMvc exchange fails
   */
  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_ownRequestNeverRendersTheStaffNote() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    // Defence in depth: even if an unredacted DTO reached the template, nothing may render it.
    when(backendApiClient.get(eq(REQUESTS_URI), anyTypeRef()))
        .thenReturn(List.of(bookingRequest(null, null, "Bar uebergeben, Zeuge greluc")));

    mockMvc
        .perform(get("/org-unit-bank"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("org-unit-bank-request-row")))
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("Bar uebergeben, Zeuge greluc"))))
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("org-unit-bank-request-expand"))))
        .andExpect(
            content()
                .string(Matchers.not(Matchers.containsString("org-unit-bank-request-detail"))));
  }

  /** A row carrying neither Begruendung nor Notiz stays flat: no chevron, no empty sub-row. */
  @Test
  @WithMockUser(roles = {"OFFICER"})
  void orgUnitBank_foreignRequestWithoutDetailRendersNoChevron() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(FOREIGN_REQUESTS_URI), anyTypeRef()))
        .thenReturn(List.of(bookingRequest(null, null, null)));

    mockMvc
        .perform(get("/org-unit-bank"))
        .andExpect(status().isOk())
        // The tab itself must be there -- otherwise this test would pass for the wrong reason.
        .andExpect(content().string(Matchers.containsString("org-unit-bank-foreign-row")))
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("org-unit-bank-foreign-expand"))))
        .andExpect(
            content()
                .string(Matchers.not(Matchers.containsString("org-unit-bank-foreign-detail"))));
  }
}
