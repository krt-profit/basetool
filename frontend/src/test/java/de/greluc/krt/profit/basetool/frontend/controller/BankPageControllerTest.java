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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankCapabilitiesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardTotalsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransferFeeRateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

@SuppressWarnings("unchecked")
class BankPageControllerTest {

  private static BankDashboardAccountDto dashboardAccount(List<BigDecimal> sparkline) {
    return new BankDashboardAccountDto(
        UUID.randomUUID(),
        "KB-0001",
        "Staffel IRIDIUM",
        "ORG_UNIT",
        "ACTIVE",
        new BigDecimal("1850000"),
        new BigDecimal("420000"),
        sparkline,
        null,
        null,
        null);
  }

  private static BankDashboardAccountDto dashboardAccount(String accountNo, String name) {
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

  /**
   * Builds a dashboard account with the full type/status/Bereich shape for the by-Bereich grouping
   * tests.
   *
   * @param name the account name
   * @param type the account type enum name
   * @param status the lifecycle enum name
   * @param bereichId the owning Bereich id, or {@code null}
   * @param bereichName the owning Bereich name, or {@code null}
   * @param department the Bereich department enum name, or {@code null}
   * @return the account payload
   */
  private static BankDashboardAccountDto groupedAccount(
      String name,
      String type,
      String status,
      UUID bereichId,
      String bereichName,
      String department) {
    return new BankDashboardAccountDto(
        UUID.randomUUID(),
        "KB-" + name,
        name,
        type,
        status,
        new BigDecimal("1000"),
        BigDecimal.ZERO,
        List.of(),
        bereichId,
        bereichName,
        department);
  }

  @Test
  void dashboard_ShouldScaleSparklineIntoPolylinePoints() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankPageController controller = new BankPageController(backendApiClient);
    Model model = new ConcurrentModel();
    BankDashboardDto dashboard =
        new BankDashboardDto(
            true,
            List.of(dashboardAccount(List.of(BigDecimal.ZERO, BigDecimal.TEN))),
            new BankDashboardTotalsDto(
                new BigDecimal("1850000"), new BigDecimal("500"), new BigDecimal("-100"), 1, 0));
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(dashboard);

    // When
    String view = controller.dashboard(null, null, null, model);

    // Then
    assertEquals("bank-dashboard", view);
    List<BankDashboardViewAssembler.BankDashboardCardView> cards =
        (List<BankDashboardViewAssembler.BankDashboardCardView>) model.getAttribute("cards");
    assertNotNull(cards);
    assertEquals(1, cards.size());
    // Rising series: first point at the padded bottom (y=24), last at the padded top (y=2).
    assertEquals("0.0,24.0 96.0,2.0", cards.get(0).sparklinePoints());
    assertFalse(cards.get(0).flat());
    // #1193 follow-up: the dashboard's booking-modal counterparty picker is server-side searched
    // (remote-bank-users), so the full-page render must NOT preload the all-users roster.
    verify(backendApiClient, never()).get(eq("/api/v1/users/lookup"), anyTypeRef());
    assertNull(
        model.getAttribute("users"), "no user roster is preloaded for the counterparty picker");
  }

  @Test
  void dashboard_ShouldRenderFlatSeriesAsMidLine() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankPageController controller = new BankPageController(backendApiClient);
    Model model = new ConcurrentModel();
    BigDecimal level = new BigDecimal("500");
    BankDashboardDto dashboard =
        new BankDashboardDto(false, List.of(dashboardAccount(List.of(level, level))), null);
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(dashboard);

    // When
    controller.dashboard(null, null, null, model);

    // Then
    List<BankDashboardViewAssembler.BankDashboardCardView> cards =
        (List<BankDashboardViewAssembler.BankDashboardCardView>) model.getAttribute("cards");
    assertNotNull(cards);
    assertTrue(cards.get(0).flat());
    assertEquals("0.0,13.0 96.0,13.0", cards.get(0).sparklinePoints());
  }

  @Test
  void dashboard_ShouldHandleEmptySparklineAndNullDashboard() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankPageController controller = new BankPageController(backendApiClient);
    Model model = new ConcurrentModel();
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(new BankDashboardDto(false, List.of(dashboardAccount(List.of())), null));

    // When
    controller.dashboard(null, null, null, model);

    // Then
    List<BankDashboardViewAssembler.BankDashboardCardView> cards =
        (List<BankDashboardViewAssembler.BankDashboardCardView>) model.getAttribute("cards");
    assertNotNull(cards);
    assertNull(cards.get(0).sparklinePoints());
    assertTrue(cards.get(0).flat());
  }

  @Test
  void dashboard_ShouldSortCardsAlphabeticallyByName() {
    // Given — backend returns cards ordered by account number, names out of alphabetical order.
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankPageController controller = new BankPageController(backendApiClient);
    Model model = new ConcurrentModel();
    BankDashboardDto dashboard =
        new BankDashboardDto(
            true,
            List.of(
                dashboardAccount("KB-0001", "Zeta Vorrat"),
                dashboardAccount("KB-0002", "alpha Reserve"),
                dashboardAccount("KB-0003", "Mittelkasse")),
            null);
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(dashboard);

    // When
    controller.dashboard(null, null, null, model);

    // Then — cards are ordered case-insensitively by account name, not by account number.
    List<BankDashboardViewAssembler.BankDashboardCardView> cards =
        (List<BankDashboardViewAssembler.BankDashboardCardView>) model.getAttribute("cards");
    assertNotNull(cards);
    assertEquals(
        List.of("alpha Reserve", "Mittelkasse", "Zeta Vorrat"),
        cards.stream().map(card -> card.account().name()).toList());
  }

  @Test
  void dashboard_byBereich_groupsAccountsWithColouredHeadersInOrder() {
    // Given — one account of each grouping-relevant shape, out of display order.
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankPageController controller = new BankPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID profit = UUID.randomUUID();
    BankDashboardDto dashboard =
        new BankDashboardDto(
            true,
            List.of(
                groupedAccount("KRT", "CARTEL", "ACTIVE", null, null, null),
                groupedAccount("KRT Bank", "CARTEL_BANK", "ACTIVE", null, null, null),
                groupedAccount("IRIDIUM", "ORG_UNIT", "ACTIVE", profit, "Profit", "PROFIT"),
                groupedAccount("Profit", "AREA", "ACTIVE", profit, "Profit", "PROFIT"),
                groupedAccount("Sonderkonto", "SPECIAL", "ACTIVE", null, null, null),
                groupedAccount("Waise", "ORG_UNIT", "ACTIVE", null, null, null),
                groupedAccount("Altkonto", "ORG_UNIT", "CLOSED", profit, "Profit", "PROFIT")),
            null);
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(dashboard);

    // When
    String view = controller.dashboard("card", "bereich", null, model);

    // Then — groups render in the fixed order: KRT rubric, Bereich groups, Sonderkonten, Ohne
    // Bereich, Geschlossen; each Bereich group leads with its AREA account and is colour-classed.
    assertEquals("bank-dashboard", view);
    assertEquals("bereich", model.getAttribute("group"));
    List<BankDashboardViewAssembler.BankDashboardGroupView> groups =
        (List<BankDashboardViewAssembler.BankDashboardGroupView>) model.getAttribute("groups");
    assertNotNull(groups);
    assertEquals(
        List.of("krt", "bereich:" + profit, "special", "ungrouped", "closed"),
        groups.stream().map(BankDashboardViewAssembler.BankDashboardGroupView::key).toList());
    BankDashboardViewAssembler.BankDashboardGroupView bereich = groups.get(1);
    assertEquals("Profit", bereich.bereichName());
    assertEquals("bank-dept--profit", bereich.deptClass());
    assertEquals(
        List.of("Profit", "IRIDIUM"),
        bereich.cards().stream().map(card -> card.account().name()).toList());
    assertEquals(
        List.of("Altkonto"),
        groups.get(4).cards().stream().map(card -> card.account().name()).toList());
  }

  @Test
  void dashboard_tableFragment_resolvesGridFragmentAndCarriesNoGroupsInAlphaMode() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankPageController controller = new BankPageController(backendApiClient);
    Model model = new ConcurrentModel();
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(new BankDashboardDto(true, List.of(), null));

    // When
    String view = controller.dashboard("table", "alpha", "bankGrid", model);

    // Then — the toggle swap re-renders only the switchable grid; alphabetical mode has no groups.
    assertEquals("bank-dashboard :: bankGrid", view);
    assertEquals("table", model.getAttribute("layout"));
    assertEquals("alpha", model.getAttribute("group"));
    assertEquals(List.of(), model.getAttribute("groups"));
  }

  @Test
  void accountDetail_ShouldNotPreloadTransferTargetRoster_AndFiltersActiveHolders() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankPageController controller = new BankPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID accountId = UUID.randomUUID();
    UUID holderA = UUID.randomUUID();
    UUID holderB = UUID.randomUUID();

    BankAccountDto self = account(accountId, "KB-0001", "ACTIVE", "1000");
    BankAccountDetailDto detail =
        new BankAccountDetailDto(
            self,
            new BigDecimal("100"),
            5,
            new BankCapabilitiesDto(true, true, true, false),
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

    when(backendApiClient.get(
            eq("/api/v1/bank/accounts/" + accountId), eq(BankAccountDetailDto.class)))
        .thenReturn(detail);
    when(backendApiClient.get(contains("/transactions"), anyTypeRef()))
        .thenReturn(
            new PageResponse<BankBookingDto>(List.of(), 0, 20, 0, 0, Collections.emptyList()));
    when(backendApiClient.get(eq("/api/v1/bank/holders"), anyTypeRef()))
        .thenReturn(
            List.of(
                new BankHolderDto(
                    holderA, UUID.randomUUID(), "alpha", true, BigDecimal.ZERO, false, 0L),
                new BankHolderDto(
                    holderB, UUID.randomUUID(), "bravo", false, BigDecimal.ZERO, false, 0L)));
    when(backendApiClient.get(
            eq("/api/v1/bank/transfer-fee-rate"), eq(BankTransferFeeRateDto.class)))
        .thenReturn(new BankTransferFeeRateDto(new BigDecimal("0.005")));

    // When
    String view = controller.accountDetail(accountId, null, null, null, null, null, null, model);

    // Then: the transfer-destination picker is a server-side account-search combobox now
    // (remote-bank-accounts, REQ-FE-017/ADR-0105), so NO transfer-target roster is preloaded and no
    // account list is fetched; active holders still exclude the deactivated one (ADR-0039).
    assertEquals("bank-account-detail", view);
    assertNull(
        model.getAttribute("transferTargets"),
        "no transfer-target roster is preloaded — the picker searches on demand");
    verify(backendApiClient, never())
        .get(org.mockito.ArgumentMatchers.startsWith("/api/v1/bank/accounts?"), anyTypeRef());
    List<BankHolderDto> activeHolders = (List<BankHolderDto>) model.getAttribute("activeHolders");
    assertNotNull(activeHolders);
    assertEquals(1, activeHolders.size());
    assertEquals("alpha", activeHolders.get(0).handle());
    List<BankHolderDto> holders = (List<BankHolderDto>) model.getAttribute("holders");
    assertNotNull(holders);
    assertEquals(2, holders.size(), "the full registry is offered for the payer/source selects");
    // paginationBaseUrl now carries the booking-history period so paging keeps the filter
    // (REQ-BANK-051).
    assertTrue(
        model
            .getAttribute("paginationBaseUrl")
            .toString()
            .startsWith("/bank/accounts/" + accountId + "?from="));
    // The transfer-fee rate feeds the withdraw/transfer modals' live fee preview (REQ-BANK-033).
    assertEquals(new BigDecimal("0.005"), model.getAttribute("transferFeeRate"));
    // #1193 follow-up: the deposit/withdrawal counterparty picker is a server-side searchable
    // combobox now (remote-bank-users), so the detail render must NOT preload the all-users roster.
    verify(backendApiClient, never()).get(eq("/api/v1/users/lookup"), anyTypeRef());
    assertNull(
        model.getAttribute("users"), "no user roster is preloaded for the counterparty picker");
  }

  @Test
  void accountDetail_ShouldHandleEmptyListsOnZeroBalance() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankPageController controller = new BankPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID accountId = UUID.randomUUID();
    BankAccountDetailDto detail =
        new BankAccountDetailDto(
            account(accountId, "KB-0001", "ACTIVE", "0"),
            BigDecimal.ZERO,
            0,
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
    when(backendApiClient.get(
            eq("/api/v1/bank/accounts/" + accountId), eq(BankAccountDetailDto.class)))
        .thenReturn(detail);
    when(backendApiClient.get(any(String.class), anyTypeRef())).thenReturn(null);

    // When
    controller.accountDetail(accountId, -3, null, null, null, null, null, model);

    // Then — the transfer destination searches on demand (no roster attribute); holders degrade to
    // an empty list.
    assertNull(model.getAttribute("transferTargets"));
    assertEquals(List.of(), model.getAttribute("activeHolders"));
  }

  // covers REQ-FE-002 — an AJAX swap request (fragment=bookings) renders only the booking-history
  // fragment and fetches ONLY the transactions page: the account-detail, holders and accounts
  // round-trips the full page does are skipped, and the model carries just bookings +
  // paginationBaseUrl.
  @Test
  void accountDetail_fragmentBookings_rendersOnlyBookingsFragment_andSkipsOtherFetches() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankPageController controller = new BankPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID accountId = UUID.randomUUID();
    PageResponse<BankBookingDto> bookings =
        new PageResponse<>(List.of(), 0, 20, 0, 0, Collections.emptyList());
    when(backendApiClient.get(contains("/transactions"), anyTypeRef())).thenReturn(bookings);

    // When
    String view = controller.accountDetail(accountId, 2, null, null, null, null, "bookings", model);

    // Then
    assertEquals("bank-account-detail :: bookings", view);
    assertEquals(bookings, model.getAttribute("bookings"));
    assertTrue(
        model
            .getAttribute("paginationBaseUrl")
            .toString()
            .startsWith("/bank/accounts/" + accountId + "?from="));
    // The fragment path must not load the account detail, holder registry or accounts list.
    verify(backendApiClient, never())
        .get(eq("/api/v1/bank/accounts/" + accountId), eq(BankAccountDetailDto.class));
    verify(backendApiClient, never()).get(eq("/api/v1/bank/holders"), anyTypeRef());
  }

  // covers REQ-FE-005 (#579) — an in-place money-write re-render (fragment=accountBody) returns the
  // whole account body fragment with the SAME full model the page builds (detail, holders,
  // transferTargets, distributionPercents, bookings) so balance, distribution AND the modals'
  // distribution-derived holder selects all refresh from one swap.
  @Test
  void accountDetail_fragmentAccountBody_rendersBodyFragment_withFullModel() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankPageController controller = new BankPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID accountId = UUID.randomUUID();
    UUID holderA = UUID.randomUUID();
    BankAccountDto self = account(accountId, "KB-0001", "ACTIVE", "1000");
    BankAccountDetailDto detail =
        new BankAccountDetailDto(
            self,
            new BigDecimal("10"),
            1,
            new BankCapabilitiesDto(true, true, true, false),
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
    when(backendApiClient.get(
            eq("/api/v1/bank/accounts/" + accountId), eq(BankAccountDetailDto.class)))
        .thenReturn(detail);
    when(backendApiClient.get(contains("/transactions"), anyTypeRef()))
        .thenReturn(
            new PageResponse<BankBookingDto>(List.of(), 0, 20, 0, 0, Collections.emptyList()));
    when(backendApiClient.get(eq("/api/v1/bank/holders"), anyTypeRef()))
        .thenReturn(
            List.of(
                new BankHolderDto(
                    holderA, UUID.randomUUID(), "alpha", true, BigDecimal.ZERO, false, 0L)));

    // When
    String view =
        controller.accountDetail(accountId, null, null, null, null, null, "accountBody", model);

    // Then
    assertEquals("bank-account-detail :: accountBody", view);
    // The accountBody fragment needs the FULL model (unlike the bookings-only fragment): detail,
    // the holder registry for the modals' selects and bookings must all be present. The transfer
    // destination is a server-side account-search combobox now (remote-bank-accounts), so no
    // transfer-target roster is preloaded.
    assertNotNull(model.getAttribute("detail"));
    assertNotNull(model.getAttribute("activeHolders"));
    assertNotNull(model.getAttribute("holders"));
    assertNull(model.getAttribute("transferTargets"));
    assertNotNull(model.getAttribute("bookings"));
  }

  // covers REQ-BANK-032 — the holder detail page loads the holder header and the first history
  // page, exposing them plus the holder-scoped pagination base url to the template.
  @Test
  void holderDetail_ShouldLoadHolderAndFirstHistoryPage() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankPageController controller = new BankPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID holderId = UUID.randomUUID();
    BankHolderDto holder =
        new BankHolderDto(
            holderId, UUID.randomUUID(), "greluc", true, new BigDecimal("1000000"), false, 0L);
    PageResponse<BankHolderBookingDto> bookings =
        new PageResponse<>(List.of(), 0, 20, 0, 0, Collections.emptyList());
    when(backendApiClient.get(eq("/api/v1/bank/holders/" + holderId), eq(BankHolderDto.class)))
        .thenReturn(holder);
    when(backendApiClient.get(contains("/transactions"), anyTypeRef())).thenReturn(bookings);

    // When
    String view = controller.holderDetail(holderId, null, null, model);

    // Then
    assertEquals("bank-holder-detail", view);
    assertEquals(holder, model.getAttribute("holder"));
    assertEquals(bookings, model.getAttribute("bookings"));
    assertEquals("/bank/holders/" + holderId, model.getAttribute("paginationBaseUrl"));
  }

  // covers REQ-FE-002 — the holder-history pager swap (fragment=holderBookings) renders only the
  // history fragment and fetches ONLY the transactions page; the holder header round-trip is
  // skipped.
  @Test
  void holderDetail_fragmentHolderBookings_rendersOnlyFragment_andSkipsHeaderFetch() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankPageController controller = new BankPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID holderId = UUID.randomUUID();
    PageResponse<BankHolderBookingDto> bookings =
        new PageResponse<>(List.of(), 0, 20, 0, 0, Collections.emptyList());
    when(backendApiClient.get(contains("/transactions"), anyTypeRef())).thenReturn(bookings);

    // When
    String view = controller.holderDetail(holderId, 2, "holderBookings", model);

    // Then
    assertEquals("bank-holder-detail :: holderBookings", view);
    assertEquals(bookings, model.getAttribute("bookings"));
    assertEquals("/bank/holders/" + holderId, model.getAttribute("paginationBaseUrl"));
    verify(backendApiClient, never())
        .get(eq("/api/v1/bank/holders/" + holderId), eq(BankHolderDto.class));
  }

  private static BankAccountDto account(UUID id, String no, String status, String balance) {
    return new BankAccountDto(
        id,
        no,
        "Konto " + no,
        "ORG_UNIT",
        status,
        null,
        null,
        new BigDecimal(balance),
        null,
        null,
        null,
        0L,
        Instant.parse("2026-01-15T10:00:00Z"));
  }
}
