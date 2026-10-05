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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountRefDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankApprovalLimitsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBalancePointDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankCapabilitiesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
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
 * Renders the two account-detail pages and the org-unit bank overview on the hand-off's bank
 * pattern: one detail fragment with head, chips, KPI tiles and the bookings tab with the balance
 * after each posting; account cards and the running requests with their approval path.
 */
@SpringBootTest
class BankAccountDetailPatternRenderTest {

  private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders a page in German.
   *
   * @param path the request path
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull String path) throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get(path).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * The account under test, with a balance target of 5.000.000.
   *
   * @return the account
   */
  private static @NotNull BankAccountDto account() {
    return new BankAccountDto(
        ACCOUNT_ID,
        "KB-0042",
        "IRI Betriebskonto",
        "ORG_UNIT",
        "ACTIVE",
        null,
        null,
        new BigDecimal("3148200"),
        new BigDecimal("5000000"),
        null,
        null,
        2L,
        Instant.parse("2026-01-15T10:00:00Z"));
  }

  /**
   * The account's detail wrapper with the given capabilities and no approval limits.
   *
   * @param caps the caller's capabilities
   * @return the detail
   */
  private static @NotNull BankAccountDetailDto detail(@NotNull BankCapabilitiesDto caps) {
    return new BankAccountDetailDto(
        account(),
        new BigDecimal("412600"),
        148L,
        caps,
        new BankApprovalLimitsDto(
            false, false, false, false, List.of(), Map.of(), null, null, List.of()));
  }

  /**
   * Two postings, newest first: a deposit with a justification and a withdrawal.
   *
   * @return the booking page
   */
  private static @NotNull PageResponse<BankBookingDto> bookings() {
    BankBookingDto deposit =
        new BankBookingDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "DEPOSIT",
            new BigDecimal("240000"),
            "alpha",
            null,
            "Erlös Quantanium-Verkauf",
            null,
            Instant.parse("2026-10-02T10:00:00Z"),
            null,
            null,
            null,
            null,
            false,
            BigDecimal.ZERO,
            "vexx",
            null);
    BankBookingDto withdrawal =
        new BankBookingDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "WITHDRAWAL",
            new BigDecimal("-62400"),
            "alpha",
            null,
            "Reparatur",
            null,
            Instant.parse("2026-10-01T10:00:00Z"),
            null,
            null,
            null,
            null,
            false,
            BigDecimal.ZERO,
            "greluc",
            null);
    return new PageResponse<>(List.of(deposit, withdrawal), 0, 50, 2L, 1, List.of());
  }

  /**
   * Counts the primary actions in the page head, which ends where the given marker starts.
   *
   * @param html the rendered page
   * @param endMarker the first markup after the page head
   * @return the number of {@code btn--cta} buttons in the head
   */
  private static int headPrimaryActions(@NotNull String html, @NotNull String endMarker) {
    int start = html.indexOf("data-testid=\"page-head\"");
    int end = html.indexOf(endMarker, start);
    assertThat(start).isPositive();
    assertThat(end).isGreaterThan(start);
    return html.substring(start, end).split("btn--cta", -1).length - 1;
  }

  /** Stubs the balance series, whose last point anchors the running balance at 3.148.200. */
  private void stubSeries() {
    when(backendApiClient.get(
            contains("/balance-series"), eq(BankBalanceSeriesDto.class), eq(ACCOUNT_ID)))
        .thenReturn(
            new BankBalanceSeriesDto(
                List.of(
                    new BankBalancePointDto(
                        Instant.parse("2026-10-01T00:00:00Z"), new BigDecimal("2908200")),
                    new BankBalancePointDto(
                        Instant.parse("2026-10-02T00:00:00Z"), new BigDecimal("3148200"))),
                new BigDecimal("5000000")));
  }

  /** The staff detail renders the shared fragment with the movement as its one primary action. */
  @Test
  @WithMockUser(roles = "BANK_MANAGEMENT")
  void staffDetailRendersTheAccountDetailPattern() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(
            eq("/api/v1/bank/accounts/{id}"), eq(BankAccountDetailDto.class), eq(ACCOUNT_ID)))
        .thenReturn(detail(new BankCapabilitiesDto(true, true, true, true)));
    when(backendApiClient.get(contains("/transactions"), anyTypeRef(), eq(ACCOUNT_ID)))
        .thenReturn(bookings());
    stubSeries();

    String html = render("/bank/accounts/" + ACCOUNT_ID);

    assertThat(html)
        .contains("class=\"page-head bank-detail-head\"")
        .containsPattern("class=\"page-eyebrow\" href=\"/bank\"")
        .containsPattern("data-testid=\"bank-account-no\"[^>]*>KB-0042<")
        .contains("data-testid=\"bank-statement-open\"")
        .contains("data-testid=\"bank-movement-open\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("bank-panel-head")
        .doesNotContain("bank-collapse-head");
    assertThat(headPrimaryActions(html, "class=\"kpi-grid bank-kpis\"")).isEqualTo(1);
    assertThat(html)
        .contains("class=\"kpi-grid bank-kpis\"")
        .contains("data-testid=\"bank-balance\"")
        .contains("Ziel 5.000.000")
        .contains("63 % erreicht")
        .contains("+412.600")
        .contains("data-testid=\"bank-detail-tab-history\"")
        .contains("data-testid=\"bank-detail-tab-info\"")
        .contains("data-tab-storage=\"bank_account_tab_\"");
    assertThat(html)
        .contains("data-testid=\"segment-historyPeriod-90d\"")
        .containsPattern("name=\"historyPeriod\" value=\"90d\" checked=\"checked\"")
        .contains("class=\"data-table data-table--stack bank-bookings-table\"")
        .containsPattern("class=\"chip chip--success\">Einzahlung<")
        .containsPattern("class=\"chip chip--danger\">Auszahlung<")
        .doesNotContain(">DEPOSIT<")
        .contains("Erlös Quantanium-Verkauf")
        .contains(">3.148.200</td>")
        .contains(">2.908.200</td>")
        .contains("data-testid=\"bank-reverse-open\"")
        .contains("data-testid=\"bank-booking-holder\"");
  }

  /** The bookings fragment carries the balance after each posting and the empty state. */
  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void bookingsFragmentRendersTheEmptyState() throws Exception {
    when(backendApiClient.get(contains("/transactions"), anyTypeRef(), eq(ACCOUNT_ID)))
        .thenReturn(new PageResponse<BankBookingDto>(List.of(), 0, 50, 0L, 0, List.of()));

    String html = render("/bank/accounts/" + ACCOUNT_ID + "?fragment=bookings");

    assertThat(html)
        .contains("data-testid=\"bank-bookings-empty\"")
        .contains("class=\"empty-state\"")
        .doesNotContain("<td colspan");
  }

  /** The org-unit detail offers the request dialog for this account and hides the staff column. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void orgUnitDetailRendersTheSharedPatternWithTheRequestAction() throws Exception {
    String detailUri = "/api/v1/org-units/bank/accounts/{id}";
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(detailUri), eq(OrgUnitBankAccountDetailDto.class), eq(ACCOUNT_ID)))
        .thenReturn(
            new OrgUnitBankAccountDetailDto(
                detail(new BankCapabilitiesDto(false, false, false, false)),
                true,
                false,
                false,
                true,
                false,
                new BigDecimal("100000"),
                false));
    when(backendApiClient.get(contains(detailUri + "/transactions"), anyTypeRef(), eq(ACCOUNT_ID)))
        .thenReturn(bookings());
    when(backendApiClient.get(eq("/api/v1/org-units/bank/transfer-targets"), anyTypeRef()))
        .thenReturn(
            List.of(
                new BankAccountRefDto(ACCOUNT_ID, "KB-0042", "IRI Betriebskonto", "ORG_UNIT"),
                new BankAccountRefDto(UUID.randomUUID(), "KB-0001", "KRT-Konto", "CARTEL")));
    stubSeries();

    String html = render("/org-unit-bank/accounts/" + ACCOUNT_ID);

    assertThat(html)
        .containsPattern("class=\"page-eyebrow\" href=\"/org-unit-bank\"")
        .contains("data-testid=\"org-unit-statement-open\"")
        .contains("data-testid=\"org-unit-bank-request-btn\"")
        .contains("id=\"org-unit-request-modal\"")
        .contains("data-refresh=\"orgUnitBankSettings\"")
        .containsPattern("<option value=\"" + ACCOUNT_ID + "\"[^>]*data-can-debit=\"true\"")
        .contains("data-limit=\"100000\"")
        .doesNotContain("hud-box")
        .doesNotContain("bank-panel-head");
    assertThat(headPrimaryActions(html, "class=\"kpi-grid bank-kpis\"")).isEqualTo(1);
    assertThat(html.split("data-can-debit=", -1)).hasSize(2);
    assertThat(html)
        .contains("data-testid=\"org-unit-bank-detail-tab-history\"")
        .contains("data-testid=\"org-unit-bank-detail-tab-info\"")
        .contains("data-tab-storage=\"org_unit_bank_account_tab_\"")
        .contains("data-testid=\"org-unit-bank-booking-row\"")
        .contains(">3.148.200</td>")
        .doesNotContain("data-testid=\"bank-reverse-open\"")
        .doesNotContain("data-testid=\"org-unit-bank-booking-holder\"");
  }

  /** The overview shows account cards, the renamed tab and the running requests' approval path. */
  @Test
  @WithMockUser(roles = "OFFICER")
  void orgUnitBankOverviewRendersCardsAndTheApprovalPath() throws Exception {
    UUID orgUnitId = UUID.randomUUID();
    OrgUnitBankBalanceDto balance =
        new OrgUnitBankBalanceDto(
            ACCOUNT_ID,
            "KB-0042",
            "IRI Betriebskonto",
            "ACTIVE",
            "ORG_UNIT",
            orgUnitId,
            "IRIDIUM",
            "IRI",
            "SQUADRON",
            new BigDecimal("3148200"),
            true,
            new BigDecimal("412600"),
            List.of(new BigDecimal("2735600"), new BigDecimal("3148200")),
            new BigDecimal("5000000"),
            true,
            null,
            false);
    BankBookingRequestDto overLimit =
        BankRequestStepsTest.request(
            UUID.randomUUID(), "PENDING", true, "BANK_MANAGEMENT", false, "2026-10-02T10:00:00Z");
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/org-units/bank/balances"), anyTypeRef()))
        .thenReturn(List.of(balance));
    when(backendApiClient.get(eq("/api/v1/org-units/bank/requests"), anyTypeRef()))
        .thenReturn(List.of(overLimit));
    when(backendApiClient.get(eq("/api/v1/org-units/bank/requests/foreign"), anyTypeRef()))
        .thenReturn(List.of(overLimit));
    when(backendApiClient.get(eq("/api/v1/org-units/bank/transfer-targets"), anyTypeRef()))
        .thenReturn(
            List.of(new BankAccountRefDto(ACCOUNT_ID, "KB-0042", "IRI Betriebskonto", "ORG_UNIT")));

    String html = render("/org-unit-bank");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Organisation<")
        .contains("data-testid=\"org-unit-bank-request-btn\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
    assertThat(headPrimaryActions(html, "id=\"org-unit-bank-results\"")).isEqualTo(1);
    assertThat(html)
        .contains("Anträge an unsere Konten")
        .doesNotContain("Fremde Anträge")
        .contains("data-testid=\"org-unit-bank-card\"")
        .contains("63 % von 5.000.000")
        .contains("+ 412.600");
    assertThat(html)
        .contains("data-testid=\"org-unit-bank-open-requests\"")
        .contains("Laufende Anträge")
        .containsPattern("data-testid=\"org-unit-bank-open-row\"")
        .contains("data-testid=\"org-unit-bank-step-done\"")
        .contains("data-testid=\"org-unit-bank-step-current\"")
        .contains("data-testid=\"org-unit-bank-step-open\"")
        .contains("aria-current=\"step\"")
        .contains("Bankleitung");
    assertThat(html.split("data-testid=\"org-unit-bank-open-row\"", -1)).hasSize(2);
  }
}
