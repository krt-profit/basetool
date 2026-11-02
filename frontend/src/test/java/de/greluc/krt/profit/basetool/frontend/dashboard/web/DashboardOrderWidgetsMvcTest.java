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

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialDemandSummaryDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialDemandSummaryRowDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.frontend.support.DashboardCatalogueFixture;
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
 * Renders the bodies of the "Aufträge zu bearbeiten", "Materialbedarf" and "Raffinerie: fertig"
 * widgets through MockMvc (REQ-UI-034): each widget reads its own endpoint with its options, shows
 * the read's total as the count and its first rows, says so when nothing qualifies, and answers a
 * backend refusal with an error status instead of a body.
 */
@SpringBootTest
class DashboardOrderWidgetsMvcTest {

  private static final String TO_PROCESS = "/dashboard/widgets/orders-to-process";
  private static final String DEMAND = "/dashboard/widgets/material-demand";
  private static final String REFINERY = "/dashboard/widgets/refinery-ready";
  private static final String ORDERS_URL =
      "/api/v1/orders?page={page}&size={size}&sort=priority,asc&status={status}";
  private static final String TO_PROCESS_TWO_URL = ORDERS_URL + ",{status}&toProcess=true";
  private static final String TO_PROCESS_ONE_URL = ORDERS_URL + "&toProcess=true";
  private static final String TO_PROCESS_UNIT_URL = TO_PROCESS_TWO_URL + "&orgUnitId={orgUnitId}";
  private static final String SUMMARY_URL = "/api/v1/orders/material-demand/summary?limit={limit}";
  private static final String SUMMARY_UNIT_URL = SUMMARY_URL + "&orgUnitId={orgUnitId}";
  private static final String READY_URL =
      "/api/v1/refinery-orders/all?page={page}&size={size}&sort=endsAt,asc"
          + "&status=OPEN,IN_PROGRESS&ready=true";
  private static final UUID UNIT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final UUID REFINERY_ORDER =
      UUID.fromString("00000000-0000-0000-0000-0000000000f1");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    DashboardCatalogueFixture.stub(backendApiClient);
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

  private static MaterialDemandSummaryRowDto demand(String material, double outstanding) {
    MaterialDto dto =
        new MaterialDto(
            UUID.randomUUID(),
            material,
            null,
            "SCU",
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            true,
            false,
            true,
            1L);
    QualityTierDto tier =
        new QualityTierDto(UUID.randomUUID(), "NONE", 0, "Keine", "None", 0, true, 1L);
    return new MaterialDemandSummaryRowDto(
        dto, "NONE", tier, outstanding, new SquadronReferenceDto(UNIT, "Alpha Staffel", "ALF"));
  }

  private static RefineryOrderListDto refinery(String location, String owner) {
    return new RefineryOrderListDto(
        REFINERY_ORDER,
        new UserReferenceDto(UUID.randomUUID(), "user", owner, owner, 1),
        new LocationDto(UUID.randomUUID(), location, null, false, false, 1L),
        null,
        Instant.parse("2026-10-01T10:00:00Z"),
        60L,
        0.0,
        0.0,
        0.0,
        0.0,
        null,
        "OPEN",
        List.of(),
        null,
        1L,
        null);
  }

  /** The default status reads the open and in-progress orders of the caller's processing units. */
  @Test
  void ordersToProcess_listTheQueueWithThePageTotal() throws Exception {
    when(backendApiClient.get(
            eq(TO_PROCESS_TWO_URL), anyTypeRef(), eq(0), eq(5), eq("OPEN"), eq("IN_PROGRESS")))
        .thenReturn(orders(7, order(42, "OPEN", 1)));

    String html = render(TO_PROCESS);

    assertThat(html)
        .contains("data-widget-content")
        .containsPattern("data-testid=\"dashboard-orders-to-process-count\"[^>]*>7<")
        .containsPattern("href=\"/orders/00000000-0000-0000-0000-0000000000d1\"")
        .contains("data-testid=\"dashboard-orders-to-process-row\"")
        .contains("#42")
        .contains("Prio 1")
        .contains("ALF")
        .contains(">Offen<")
        .containsPattern("href=\"/orders\\?scope=TO_PROCESS\"")
        .contains("Alle anzeigen →")
        .doesNotContain("dashboard-orders-to-process-empty")
        .doesNotContain("<button");
  }

  /** A pinned unit is asked for by id, and its rows carry no unit label. */
  @Test
  void ordersToProcess_ofAPinnedUnitAskForThatUnitAndHonourStatusAndRows() throws Exception {
    when(backendApiClient.get(
            eq(TO_PROCESS_ONE_URL + "&orgUnitId={orgUnitId}"),
            anyTypeRef(),
            eq(0),
            eq(3),
            eq("IN_PROGRESS"),
            eq(UNIT)))
        .thenReturn(orders(1, order(43, "IN_PROGRESS", 2)));

    String html =
        render(TO_PROCESS, Map.of("unit", UNIT.toString(), "rows", "3", "status", "IN_PROGRESS"));

    assertThat(html)
        .contains("#43")
        .contains(">In Bearbeitung<")
        .doesNotContain("data-testid=\"dashboard-orders-to-process-unit\"");
  }

  /** Following the context labels each row with its unit. */
  @Test
  void ordersToProcess_followingTheContextLabelTheUnit() throws Exception {
    when(backendApiClient.get(
            eq(TO_PROCESS_TWO_URL), anyTypeRef(), eq(0), eq(5), eq("OPEN"), eq("IN_PROGRESS")))
        .thenReturn(orders(1, order(42, "OPEN", 1)));

    assertThat(render(TO_PROCESS)).contains("data-testid=\"dashboard-orders-to-process-unit\"");
  }

  /** A status of another widget falls back to the default. */
  @Test
  void ordersToProcess_ignoreAStatusTheWidgetDoesNotOffer() throws Exception {
    when(backendApiClient.get(
            eq(TO_PROCESS_TWO_URL), anyTypeRef(), eq(0), eq(5), eq("OPEN"), eq("IN_PROGRESS")))
        .thenReturn(orders(0));

    render(TO_PROCESS, Map.of("status", "UNREAD"));

    verify(backendApiClient)
        .get(eq(TO_PROCESS_TWO_URL), anyTypeRef(), eq(0), eq(5), eq("OPEN"), eq("IN_PROGRESS"));
  }

  /** Without a queue the widget says so. */
  @Test
  void ordersToProcess_withoutOrdersShowTheEmptyState() throws Exception {
    when(backendApiClient.get(
            eq(TO_PROCESS_TWO_URL), anyTypeRef(), eq(0), eq(5), eq("OPEN"), eq("IN_PROGRESS")))
        .thenReturn(orders(0));

    assertThat(render(TO_PROCESS))
        .contains("data-testid=\"dashboard-orders-to-process-empty\"")
        .contains("Kein Auftrag zu bearbeiten.");
  }

  /** The summary read carries the rows, its count is the open buckets and the rows are cut. */
  @Test
  void materialDemand_showsTheOpenBucketsWithTheirGap() throws Exception {
    when(backendApiClient.get(eq(SUMMARY_URL), eq(MaterialDemandSummaryDto.class), eq(5)))
        .thenReturn(
            new MaterialDemandSummaryDto(
                9, List.of(demand("Titanium", 12.5), demand("Quartz", 3))));

    String html = render(DEMAND);

    assertThat(html)
        .containsPattern("data-testid=\"dashboard-material-demand-count\"[^>]*>9<")
        .contains("data-testid=\"dashboard-material-demand-row\"")
        .contains("Titanium")
        .contains("Quartz")
        .contains("12,500 SCU")
        .contains("Keine")
        .contains("data-testid=\"dashboard-material-demand-unit\"")
        .containsPattern("href=\"/orders/material-demand\"");
  }

  /** A pinned unit and the rows option go into the read; the rows carry no unit label. */
  @Test
  void materialDemand_ofAPinnedUnitAskForThatUnit() throws Exception {
    when(backendApiClient.get(
            eq(SUMMARY_UNIT_URL), eq(MaterialDemandSummaryDto.class), eq(10), eq(UNIT)))
        .thenReturn(new MaterialDemandSummaryDto(1, List.of(demand("Titanium", 1))));

    String html = render(DEMAND, Map.of("unit", UNIT.toString(), "rows", "10"));

    assertThat(html)
        .contains("Titanium")
        .doesNotContain("data-testid=\"dashboard-material-demand-unit\"");
  }

  /** Without a gap the widget says so. */
  @Test
  void materialDemand_withoutAGapShowsTheEmptyState() throws Exception {
    when(backendApiClient.get(eq(SUMMARY_URL), eq(MaterialDemandSummaryDto.class), eq(5)))
        .thenReturn(new MaterialDemandSummaryDto(0, List.of()));

    assertThat(render(DEMAND))
        .contains("data-testid=\"dashboard-material-demand-empty\"")
        .contains("Kein offener Materialbedarf.");
  }

  /** The ready segment is read from page zero with the row count and counted by its total. */
  @Test
  void refineryReady_listTheFinishedJobs() throws Exception {
    when(backendApiClient.get(eq(READY_URL), anyTypeRef(), eq(0), eq(5)))
        .thenReturn(
            new PageResponse<>(List.of(refinery("ARC-L1", "greluc")), 0, 5, 3L, 1, List.of()));

    String html = render(REFINERY);

    assertThat(html)
        .containsPattern("data-testid=\"dashboard-refinery-ready-count\"[^>]*>3<")
        .contains("data-testid=\"dashboard-refinery-ready-row\"")
        .contains("ARC-L1")
        .contains("greluc")
        .containsPattern("href=\"/refinery-orders/00000000-0000-0000-0000-0000000000f1\"")
        .containsPattern("href=\"/refinery-orders\\?view=READY\"");
  }

  /** The rows option is the page size. */
  @Test
  void refineryReady_honoursTheRowsAndShowsTheEmptyState() throws Exception {
    when(backendApiClient.get(eq(READY_URL), anyTypeRef(), eq(0), eq(10)))
        .thenReturn(new PageResponse<>(List.of(), 0, 10, 0L, 0, List.of()));

    assertThat(render(REFINERY, Map.of("rows", "10")))
        .contains("data-testid=\"dashboard-refinery-ready-empty\"")
        .contains("Keine Raffinerie-Aufträge fertig.");
  }

  /**
   * A refusal of the backend is answered with the error status the script turns into the error
   * state.
   */
  @Test
  void aRefusedReadAnswersWithTheBackendsStatus() throws Exception {
    when(backendApiClient.get(eq(SUMMARY_URL), eq(MaterialDemandSummaryDto.class), eq(5)))
        .thenThrow(new BackendServiceException("refused", null, 403));
    when(backendApiClient.get(eq(READY_URL), anyTypeRef(), eq(0), eq(5)))
        .thenThrow(new BackendServiceException("down", null, 503));

    mockMvc
        .perform(get(DEMAND).header("X-Requested-With", "XMLHttpRequest").with(oidcLogin()))
        .andExpect(status().is4xxClientError());
    mockMvc
        .perform(get(REFINERY).header("X-Requested-With", "XMLHttpRequest").with(oidcLogin()))
        .andExpect(status().is5xxServerError());
  }

  /** The English bundle carries the widget texts. */
  @Test
  void materialDemand_isTranslated() throws Exception {
    when(backendApiClient.get(eq(SUMMARY_URL), eq(MaterialDemandSummaryDto.class), eq(5)))
        .thenReturn(new MaterialDemandSummaryDto(0, List.of()));

    String html =
        mockMvc
            .perform(
                get(DEMAND)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .with(oidcLogin())
                    .param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("No open material demand.");
  }
}
