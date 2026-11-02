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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialRequestDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.NeedDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyRefDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the bodies of the "Materialbörse", "Bedarf &amp; Spenden" and "Meine Bankanträge" widgets
 * through MockMvc (REQ-UI-034): each reads its own endpoint with its options, shows the read's
 * total as the count and its first rows, and says so when nothing qualifies.
 */
@SpringBootTest
class DashboardTradeWidgetsMvcTest {

  private static final String MARKET = "/dashboard/widgets/market";
  private static final String NEEDS = "/dashboard/widgets/supply-needs";
  private static final String REQUESTS = "/dashboard/widgets/bank-requests";
  private static final String OFFERS_URL =
      "/api/v1/material-exchange/offers?tab=alle&size=5&sort=neu";
  private static final String WANTED_URL =
      "/api/v1/material-exchange/requests?tab=alle&size=5&sort=neu";
  private static final String NEEDS_URL =
      "/api/v1/supply/needs?page={page}&size={size}&sort=createdAt,desc&status={status}";
  private static final String BANK_REQUESTS_URL = "/api/v1/bank/org-units/requests";
  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID UNIT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private String render(String path) throws Exception {
    return render(path, Map.of());
  }

  private String render(String path, Map<String, String> params) throws Exception {
    var request =
        get(path)
            .header("X-Requested-With", "XMLHttpRequest")
            .with(oidcLogin())
            .locale(Locale.GERMAN);
    params.forEach(request::param);
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static MaterialExchangeOfferDto offer(String name, int quality, Instant releasedAt) {
    return new MaterialExchangeOfferDto(
        ID,
        "MATERIAL",
        new MaterialReferenceDto(UUID.randomUUID(), name, "SCU"),
        null,
        null,
        null,
        null,
        List.of(),
        false,
        quality,
        12.5,
        12.5,
        releasedAt,
        null,
        0,
        List.of(),
        false,
        "ACTIVE",
        1L,
        false);
  }

  private static MaterialRequestDto wanted(String name, int minQuality, Instant postedAt) {
    return new MaterialRequestDto(
        ID,
        "MATERIAL",
        new MaterialReferenceDto(UUID.randomUUID(), name, "SCU"),
        null,
        null,
        null,
        40.0,
        minQuality,
        null,
        List.of(),
        false,
        postedAt,
        null,
        0,
        List.of(),
        false,
        "ACTIVE",
        1L);
  }

  private static <T> PageResponse<T> page(long total, List<T> content) {
    return new PageResponse<>(content, 0, 5, total, 2, List.of());
  }

  private static NeedDto need(String material, double amount, double fulfilled) {
    return new NeedDto(
        ID,
        UNIT,
        "Alpha Staffel",
        "ALF",
        new SupplyRefDto(UUID.randomUUID(), material),
        null,
        "SCU",
        null,
        amount,
        fulfilled,
        amount - fulfilled,
        "NORMAL",
        "OPEN",
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        1L,
        Instant.parse("2026-10-01T10:00:00Z"));
  }

  private static BankBookingRequestDto bankRequest(String type, String status, long amount) {
    return new BankBookingRequestDto(
        ID,
        ID,
        "KB-0001",
        "ALF Betriebskonto",
        UNIT,
        "Alpha Staffel",
        "ALF",
        type,
        BigDecimal.valueOf(amount),
        null,
        null,
        null,
        status,
        "Handle",
        null,
        null,
        null,
        null,
        null,
        null,
        Instant.parse("2026-10-01T10:00:00Z"),
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
        null,
        null,
        1L,
        null);
  }

  /** Both boards are read and merged, the newest entry first, counted by both totals. */
  @Test
  void market_mergesOffersAndWantedListingsNewestFirst() throws Exception {
    when(backendApiClient.get(eq(OFFERS_URL), anyTypeRef()))
        .thenReturn(
            page(
                7,
                List.of(
                    offer("Quantanium", 920, Instant.parse("2026-10-02T10:00:00Z")),
                    offer("Taranite", 700, Instant.parse("2026-09-01T10:00:00Z")))));
    when(backendApiClient.get(eq(WANTED_URL), anyTypeRef()))
        .thenReturn(
            page(3, List.of(wanted("Agricium", 600, Instant.parse("2026-10-03T10:00:00Z")))));

    String html = render(MARKET);

    assertThat(html)
        .containsPattern("data-testid=\"dashboard-market-count\"[^>]*>10<")
        .containsPattern("(?s)Agricium.*Quantanium.*Taranite")
        .contains("data-testid=\"dashboard-market-row\"")
        .contains("Angebot")
        .contains("Gesuch")
        .contains("Q 920")
        .contains("12,500 SCU")
        .containsPattern("href=\"/materialboerse\"")
        .doesNotContain("dashboard-market-empty");
  }

  /** The offers status reads the offer board only and links to it. */
  @Test
  void market_offersStatusReadsOnlyTheOfferBoard() throws Exception {
    when(backendApiClient.get(eq(OFFERS_URL), anyTypeRef()))
        .thenReturn(page(1, List.of(offer("Quantanium", 920, Instant.now()))));

    String html = render(MARKET, Map.of("status", "OFFERS"));

    verify(backendApiClient, never()).get(eq(WANTED_URL), anyTypeRef());
    assertThat(html).contains("Quantanium").doesNotContain("Gesuch</span>");
  }

  /** The requests status reads the wanted board only and links to its view. */
  @Test
  void market_requestsStatusReadsOnlyTheWantedBoardAndLinksToIt() throws Exception {
    when(backendApiClient.get(eq(WANTED_URL), anyTypeRef()))
        .thenReturn(page(1, List.of(wanted("Agricium", 600, Instant.now()))));

    String html = render(MARKET, Map.of("status", "REQUESTS"));

    verify(backendApiClient, never()).get(eq(OFFERS_URL), anyTypeRef());
    assertThat(html)
        .contains("Agricium")
        .containsPattern("href=\"/materialboerse\\?view=requests\"");
  }

  /** The rows option sets the page size of both reads and cuts the merged list. */
  @Test
  void market_cutsTheMergedListToTheRows() throws Exception {
    when(backendApiClient.get(eq(OFFERS_URL.replace("size=5", "size=3")), anyTypeRef()))
        .thenReturn(
            page(
                4,
                List.of(
                    offer("A-Offer", 900, Instant.parse("2026-10-09T10:00:00Z")),
                    offer("B-Offer", 900, Instant.parse("2026-10-08T10:00:00Z")),
                    offer("C-Offer", 900, Instant.parse("2026-10-07T10:00:00Z")))));
    when(backendApiClient.get(eq(WANTED_URL.replace("size=5", "size=3")), anyTypeRef()))
        .thenReturn(
            page(1, List.of(wanted("D-Wanted", 500, Instant.parse("2026-10-06T10:00:00Z")))));

    String html = render(MARKET, Map.of("rows", "3"));

    assertThat(html)
        .containsPattern("data-testid=\"dashboard-market-count\"[^>]*>5<")
        .contains("A-Offer")
        .contains("C-Offer")
        .doesNotContain("D-Wanted");
  }

  /** A status of another widget falls back to both boards. */
  @Test
  void market_ignoresAStatusTheWidgetDoesNotOffer() throws Exception {
    when(backendApiClient.get(eq(OFFERS_URL), anyTypeRef())).thenReturn(page(0, List.of()));
    when(backendApiClient.get(eq(WANTED_URL), anyTypeRef())).thenReturn(page(0, List.of()));

    render(MARKET, Map.of("status", "UNREAD"));

    verify(backendApiClient).get(eq(OFFERS_URL), anyTypeRef());
    verify(backendApiClient).get(eq(WANTED_URL), anyTypeRef());
  }

  /** Without an entry the widget says so. */
  @Test
  void market_withoutEntriesShowsTheEmptyState() throws Exception {
    when(backendApiClient.get(eq(OFFERS_URL), anyTypeRef())).thenReturn(page(0, List.of()));
    when(backendApiClient.get(eq(WANTED_URL), anyTypeRef())).thenReturn(page(0, List.of()));

    String html = render(MARKET);

    assertThat(html)
        .contains("data-testid=\"dashboard-market-empty\"")
        .contains("Zurzeit gibt es keine offenen Einträge.");
  }

  /** The open needs are counted by the page total and show their unit while following. */
  @Test
  void supplyNeeds_listTheOpenNeedsWithTheirCoverage() throws Exception {
    when(backendApiClient.get(eq(NEEDS_URL), anyTypeRef(), eq(0), eq(5), eq("OPEN")))
        .thenReturn(page(9, List.of(need("Quantanium", 100, 40))));

    String html = render(NEEDS);

    assertThat(html)
        .containsPattern("data-testid=\"dashboard-supply-needs-count\"[^>]*>9<")
        .contains("Quantanium")
        .contains("data-testid=\"dashboard-supply-needs-unit\"")
        .contains("40 %")
        .containsPattern("data-krtm-width=\"40")
        .containsPattern("href=\"/supply/needs\"")
        .doesNotContain("dashboard-supply-needs-empty");
  }

  /** The uncovered status asks the backend for the uncovered coverage. */
  @Test
  void supplyNeeds_uncoveredStatusAsksForTheCoverage() throws Exception {
    when(backendApiClient.get(
            eq(NEEDS_URL + "&coverage={coverage}"),
            anyTypeRef(),
            eq(0),
            eq(5),
            eq("OPEN"),
            eq("UNCOVERED")))
        .thenReturn(page(1, List.of(need("Taranite", 10, 0))));

    String html = render(NEEDS, Map.of("status", "UNCOVERED"));

    assertThat(html).contains("Taranite");
  }

  /** A pinned unit is asked for by id and its rows carry no unit label. */
  @Test
  void supplyNeeds_ofAPinnedUnitAskForThatUnitAndOmitTheLabel() throws Exception {
    when(backendApiClient.get(
            eq(
                "/api/v1/supply/needs?page={page}&size={size}&sort=createdAt,desc"
                    + "&orgUnitId={orgUnitId}&status={status}"),
            anyTypeRef(),
            eq(0),
            eq(10),
            eq(UNIT),
            eq("OPEN")))
        .thenReturn(page(1, List.of(need("Taranite", 10, 5))));

    String html = render(NEEDS, Map.of("unit", UNIT.toString(), "rows", "10"));

    assertThat(html)
        .contains("Taranite")
        .doesNotContain("dashboard-supply-needs-unit")
        .containsPattern("href=\"/supply/needs\\?orgUnitId=" + UNIT + "\"");
  }

  /** Without an open need the widget says so. */
  @Test
  void supplyNeeds_withoutNeedsShowTheEmptyState() throws Exception {
    when(backendApiClient.get(eq(NEEDS_URL), anyTypeRef(), eq(0), eq(5), eq("OPEN")))
        .thenReturn(page(0, List.of()));

    String html = render(NEEDS);

    assertThat(html)
        .contains("data-testid=\"dashboard-supply-needs-empty\"")
        .contains("Zurzeit gibt es keinen offenen Bedarf.");
  }

  /** The default status shows the pending requests only and counts them. */
  @Test
  void bankRequests_defaultShowsThePendingOnes() throws Exception {
    when(backendApiClient.get(eq(BANK_REQUESTS_URL), anyTypeRef()))
        .thenReturn(
            List.of(
                bankRequest("WITHDRAWAL", "PENDING", 5_000),
                bankRequest("DEPOSIT", "CONFIRMED", 7_000),
                bankRequest("TRANSFER", "PENDING", 9_000)));

    String html = render(REQUESTS);

    assertThat(html)
        .containsPattern("data-testid=\"dashboard-bank-requests-count\"[^>]*>2<")
        .contains("Auszahlung")
        .contains("Transfer")
        .contains("Ausstehend")
        .contains("5.000")
        .contains("ALF Betriebskonto")
        .doesNotContain("Einzahlung")
        .containsPattern("href=\"/org-unit-bank\"");
  }

  /** The decided status shows the requests that reached a final state. */
  @Test
  void bankRequests_decidedShowsTheFinalOnes() throws Exception {
    when(backendApiClient.get(eq(BANK_REQUESTS_URL), anyTypeRef()))
        .thenReturn(
            List.of(
                bankRequest("WITHDRAWAL", "PENDING", 5_000),
                bankRequest("DEPOSIT", "CONFIRMED", 7_000),
                bankRequest("TRANSFER", "REJECTED", 9_000)));

    String html = render(REQUESTS, Map.of("status", "DECIDED"));

    assertThat(html)
        .containsPattern("data-testid=\"dashboard-bank-requests-count\"[^>]*>2<")
        .contains("Bestätigt")
        .contains("Abgelehnt")
        .doesNotContain("Ausstehend");
  }

  /** All requests are cut to the rows (three by default) and counted in full. */
  @Test
  void bankRequests_allCutsToTheRowsAndCountsEverything() throws Exception {
    when(backendApiClient.get(eq(BANK_REQUESTS_URL), anyTypeRef()))
        .thenReturn(
            List.of(
                bankRequest("DEPOSIT", "PENDING", 1_000),
                bankRequest("DEPOSIT", "CONFIRMED", 2_000),
                bankRequest("DEPOSIT", "REJECTED", 3_000),
                bankRequest("DEPOSIT", "CANCELLED", 4_000)));

    String html = render(REQUESTS, Map.of("status", "ALL"));

    assertThat(html)
        .containsPattern("data-testid=\"dashboard-bank-requests-count\"[^>]*>4<")
        .contains("1.000")
        .contains("3.000")
        .doesNotContain("4.000");
  }

  /** Without a request the widget says so. */
  @Test
  void bankRequests_withoutRequestsShowTheEmptyState() throws Exception {
    when(backendApiClient.get(eq(BANK_REQUESTS_URL), anyTypeRef())).thenReturn(List.of());

    String html = render(REQUESTS);

    assertThat(html)
        .contains("data-testid=\"dashboard-bank-requests-empty\"")
        .contains("Keine Anträge.");
  }

  /** The English bundle carries the widget texts. */
  @Test
  void market_isTranslated() throws Exception {
    when(backendApiClient.get(eq(OFFERS_URL), anyTypeRef())).thenReturn(page(0, List.of()));
    when(backendApiClient.get(eq(WANTED_URL), anyTypeRef())).thenReturn(page(0, List.of()));

    String html =
        mockMvc
            .perform(
                get(MARKET)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .with(oidcLogin())
                    .param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("There are no open entries right now.");
  }
}
