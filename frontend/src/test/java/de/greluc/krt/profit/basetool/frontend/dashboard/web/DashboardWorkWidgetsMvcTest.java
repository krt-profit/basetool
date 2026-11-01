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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.bank.model.OrgUnitBankVisibleAccountDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.StockLevelDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
 * Renders the bodies of the "Ich bearbeite", "Bestand niedrig" and "Bank" widgets through MockMvc
 * (REQ-UI-034): each widget reads its own endpoint with its options, shows the read's total as the
 * count and its first rows, and says so when nothing qualifies.
 */
@SpringBootTest
class DashboardWorkWidgetsMvcTest {

  private static final String ASSIGNED = "/dashboard/widgets/assigned-orders";
  private static final String LOW_STOCK = "/dashboard/widgets/low-stock";
  private static final String BANK = "/dashboard/widgets/bank";
  private static final String ORDERS_URL =
      "/api/v1/orders?page={page}&size={size}&sort=priority,asc&status={status}";
  private static final String ORDERS_TWO_STATUSES_URL = ORDERS_URL + ",{status}&assignedToMe=true";
  private static final String ORDERS_ONE_STATUS_URL = ORDERS_URL + "&assignedToMe=true";
  private static final String LOW_URL = "/api/v1/inventory/stock-levels?lowOnly=true";
  private static final String VISIBLE_URL =
      "/api/v1/bank/org-units/accounts/visible?page=0&size={size}";
  private static final UUID UNIT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

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

  private String render(String path, String... params) throws Exception {
    var request =
        get(path)
            .header("X-Requested-With", "XMLHttpRequest")
            .with(oidcLogin())
            .locale(Locale.GERMAN);
    for (int i = 0; i < params.length; i += 2) {
      request.param(params[i], params[i + 1]);
    }
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static JobOrderDto order(int displayId, String status, int priority) {
    return new JobOrderDto(
        ORDER,
        displayId,
        new SquadronReferenceDto(UNIT, "Alpha Staffel", "ALF"),
        null,
        null,
        null,
        priority,
        status,
        "MATERIAL",
        false,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Instant.parse("2026-10-01T10:00:00Z"),
        1L,
        false,
        false,
        false);
  }

  private static PageResponse<JobOrderDto> orders(long total, JobOrderDto... content) {
    return new PageResponse<>(List.of(content), 0, 5, total, 2, List.of());
  }

  private static StockLevelDto level(String material, double fillRatio, String shorthand) {
    return new StockLevelDto(
        UUID.randomUUID(),
        1L,
        new OrgUnitReferenceDto(UNIT, "Alpha Staffel", shorthand, "SQUADRON"),
        new MaterialReferenceDto(UUID.randomUUID(), material, "SCU"),
        null,
        null,
        10,
        20,
        10 * fillRatio,
        10 - 10 * fillRatio,
        fillRatio,
        true,
        0,
        false);
  }

  private static OrgUnitBankVisibleAccountDto account(
      String name, long balance, BigDecimal target, long delta) {
    return new OrgUnitBankVisibleAccountDto(
        ACCOUNT,
        "KB-0001",
        name,
        "ORG_UNIT",
        UNIT,
        "Alpha Staffel",
        "ALF",
        "SQUADRON",
        BigDecimal.valueOf(balance),
        target,
        BigDecimal.valueOf(delta),
        List.of(BigDecimal.valueOf(balance - delta), BigDecimal.valueOf(balance)));
  }

  /** The default status reads the open and in-progress orders and counts the page total. */
  @Test
  void assignedOrders_listTheCallersOrdersWithThePageTotal() throws Exception {
    when(backendApiClient.get(
            eq(ORDERS_TWO_STATUSES_URL), anyTypeRef(), eq(0), eq(5), eq("OPEN"), eq("IN_PROGRESS")))
        .thenReturn(orders(7, order(42, "OPEN", 1)));

    String html = render(ASSIGNED);

    assertThat(html)
        .contains("data-widget-content")
        .containsPattern("data-testid=\"dashboard-assigned-orders-count\"[^>]*>7<")
        .containsPattern("href=\"/orders/00000000-0000-0000-0000-0000000000d1\"")
        .contains("#42")
        .contains("ALF")
        .contains(">Offen<")
        .containsPattern("href=\"/orders\\?scope=ASSIGNED\"")
        .contains("Alle anzeigen →")
        .doesNotContain("dashboard-assigned-orders-empty");
  }

  /** The rows and the status option decide the page size and the status filter of the read. */
  @Test
  void assignedOrders_honourTheRowsAndTheStatusOption() throws Exception {
    when(backendApiClient.get(
            eq(ORDERS_ONE_STATUS_URL), anyTypeRef(), eq(0), eq(10), eq("IN_PROGRESS")))
        .thenReturn(orders(1, order(43, "IN_PROGRESS", 2)));

    String html = render(ASSIGNED, "rows", "10", "status", "IN_PROGRESS");

    verify(backendApiClient)
        .get(eq(ORDERS_ONE_STATUS_URL), anyTypeRef(), eq(0), eq(10), eq("IN_PROGRESS"));
    assertThat(html).contains("#43").contains(">In Bearbeitung<");
  }

  /** A status of another widget falls back to the default instead of breaking the read. */
  @Test
  void assignedOrders_ignoreAStatusTheWidgetDoesNotOffer() throws Exception {
    when(backendApiClient.get(
            eq(ORDERS_TWO_STATUSES_URL), anyTypeRef(), eq(0), eq(5), eq("OPEN"), eq("IN_PROGRESS")))
        .thenReturn(orders(0));

    render(ASSIGNED, "status", "UNREAD");

    verify(backendApiClient)
        .get(
            eq(ORDERS_TWO_STATUSES_URL), anyTypeRef(), eq(0), eq(5), eq("OPEN"), eq("IN_PROGRESS"));
  }

  /** Without an assignee entry the widget says so. */
  @Test
  void assignedOrders_withoutOrdersShowTheEmptyState() throws Exception {
    when(backendApiClient.get(
            eq(ORDERS_TWO_STATUSES_URL), anyTypeRef(), eq(0), eq(5), eq("OPEN"), eq("IN_PROGRESS")))
        .thenReturn(orders(0));

    String html = render(ASSIGNED);

    assertThat(html)
        .contains("data-testid=\"dashboard-assigned-orders-empty\"")
        .contains("Du bearbeitest zurzeit keinen Auftrag.")
        .doesNotContain("order-row");
  }

  /** The low levels are cut to the rows, counted in full and labelled with their unit. */
  @Test
  void lowStock_showsTheFirstRowsAndCountsAllLowLevels() throws Exception {
    when(backendApiClient.get(eq(LOW_URL), anyTypeRef()))
        .thenReturn(
            List.of(
                level("Quantanium", 0.0, "ALF"),
                level("Taranite", 0.4, "ALF"),
                level("Agricium", 0.7, "ALF")));

    String html = render(LOW_STOCK);

    assertThat(html)
        .containsPattern("data-testid=\"dashboard-low-stock-count\"[^>]*>3<")
        .contains("Quantanium")
        .contains("Taranite")
        .contains("Agricium")
        .contains("40 %")
        .contains("data-testid=\"dashboard-low-stock-unit\"")
        .containsPattern("href=\"/inventory/stock-levels\"");
  }

  /** The rows option cuts the list, the count stays the total. */
  @Test
  void lowStock_cutsTheListToTheRowsOption() throws Exception {
    when(backendApiClient.get(eq(LOW_URL), anyTypeRef()))
        .thenReturn(
            List.of(
                level("Quantanium", 0.0, "ALF"),
                level("Taranite", 0.4, "ALF"),
                level("Agricium", 0.7, "ALF"),
                level("Bexalite", 0.8, "ALF")));

    String html = render(LOW_STOCK, "rows", "3");

    assertThat(html)
        .containsPattern("data-testid=\"dashboard-low-stock-count\"[^>]*>4<")
        .contains("Agricium")
        .doesNotContain("Bexalite");
  }

  /** A pinned unit is asked for by id and its rows carry no unit label. */
  @Test
  void lowStock_ofAPinnedUnitAsksForThatUnitAndOmitsTheLabel() throws Exception {
    when(backendApiClient.get(eq(LOW_URL + "&orgUnitId={unit}"), anyTypeRef(), eq(UNIT)))
        .thenReturn(List.of(level("Quantanium", 0.0, "ALF")));

    String html = render(LOW_STOCK, "unit", UNIT.toString());

    assertThat(html).contains("Quantanium").doesNotContain("dashboard-low-stock-unit");
  }

  /** Without a low level the widget says so. */
  @Test
  void lowStock_withoutLowLevelsShowsTheEmptyState() throws Exception {
    when(backendApiClient.get(eq(LOW_URL), anyTypeRef())).thenReturn(List.of());

    String html = render(LOW_STOCK);

    assertThat(html)
        .contains("data-testid=\"dashboard-low-stock-empty\"")
        .contains("Kein Bestand unter dem Mindestbestand.");
  }

  /** The bank widget asks for page zero with the row count and counts the page total. */
  @Test
  void bank_showsBalanceTargetAndTrendOfTheFirstAccounts() throws Exception {
    when(backendApiClient.get(eq(VISIBLE_URL), anyTypeRef(), eq(3)))
        .thenReturn(
            new PageResponse<>(
                List.of(account("KRT Hauptkonto", 1_500_000, BigDecimal.valueOf(3_000_000), 120)),
                0,
                3,
                5L,
                2,
                List.of()));

    String html = render(BANK);

    assertThat(html)
        .containsPattern("data-testid=\"dashboard-bank-count\"[^>]*>5<")
        .contains("KRT Hauptkonto")
        .contains("KB-0001")
        .contains("1.500.000")
        .contains("aUEC")
        .containsPattern("data-krtm-width=\"50")
        .contains("50 % von 3.000.000")
        .contains("kpi-delta--pos")
        .contains("<polyline")
        .containsPattern("href=\"/org-unit-bank/accounts/00000000-0000-0000-0000-0000000000e1\"")
        .containsPattern("href=\"/org-unit-bank\"");
  }

  /** An account without a target shows no bar, and the rows option is the page size. */
  @Test
  void bank_withoutATargetShowsNoBarAndHonoursTheRows() throws Exception {
    when(backendApiClient.get(eq(VISIBLE_URL), anyTypeRef(), eq(10)))
        .thenReturn(
            new PageResponse<>(
                List.of(account("KRT Hauptkonto", 900, null, -5)), 0, 10, 1L, 1, List.of()));

    String html = render(BANK, "rows", "10");

    assertThat(html)
        .contains("kein Ziel")
        .contains("kpi-delta--neg")
        .doesNotContain("data-krtm-width");
  }

  /** The English bundle carries the widget texts. */
  @Test
  void lowStock_isTranslated() throws Exception {
    when(backendApiClient.get(eq(LOW_URL), anyTypeRef())).thenReturn(List.of());

    String html =
        mockMvc
            .perform(
                get(LOW_STOCK)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .with(oidcLogin())
                    .param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("No stock below its minimum.");
  }
}
