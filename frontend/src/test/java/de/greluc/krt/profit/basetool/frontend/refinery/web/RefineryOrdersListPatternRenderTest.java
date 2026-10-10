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

package de.greluc.krt.profit.basetool.frontend.refinery.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.RefiningMethodDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderListDto;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the refinery list on the list pattern (REQ-UI-027, REQ-REFINERY-019): page head with one
 * primary action, the run segment with counters, search and own-orders switch, the yield / refinery
 * / done / owner columns with a progress bar, ready rows marked and storable, and the empty state.
 */
@SpringBootTest
class RefineryOrdersListPatternRenderTest {

  /** The viewing member, owner of every order below. */
  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");

  /** Id of the order whose end has passed. */
  private static final UUID READY_ID = UUID.fromString("00000000-0000-0000-0000-000000001042");

  /** Id of the order still running. */
  private static final UUID RUNNING_ID = UUID.fromString("00000000-0000-0000-0000-000000001051");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders {@code /refinery-orders} in German as the viewer, with the given page from the backend.
   *
   * @param page the page every list call returns, except a ready call, which gets the ready order
   * @param query the query string, without {@code ?}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(
      @NotNull PageResponse<RefineryOrderListDto> page, @NotNull String query) throws Exception {
    stubLists(page);
    return perform(query);
  }

  /**
   * Stubs every list call to return {@code page}, except a ready call, which gets the ready order.
   *
   * @param page the page every non-ready list call returns
   */
  private void stubLists(@NotNull PageResponse<RefineryOrderListDto> page) {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(page);
    when(backendApiClient.get(contains("ready=true"), anyTypeRef(), any(Object[].class)))
        .thenReturn(onlyOrder(order(READY_ID, 120, 60, "Quantanium-Erz", "Quantanium")));
  }

  /**
   * Renders {@code /refinery-orders} in German as the viewer against the stubs already in place.
   *
   * @param query the query string, without {@code ?}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String perform(@NotNull String query) throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(
            get("/refinery-orders?" + query)
                .locale(Locale.GERMAN)
                .with(oidcLogin().idToken(token -> token.subject(VIEWER.toString()))))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * A material of the catalogue.
   *
   * @param name the material name
   * @return the material
   */
  private static @NotNull MaterialDto material(@NotNull String name) {
    return new MaterialDto(
        UUID.randomUUID(),
        name,
        null,
        "SCU",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        1L);
  }

  /**
   * An open order of the viewer that started {@code startedMinutesAgo} minutes ago.
   *
   * @param id the order id
   * @param startedMinutesAgo minutes since the start
   * @param durationMinutes the run's duration
   * @param ore the input material name
   * @param refined the output material name
   * @return the order
   */
  private static @NotNull RefineryOrderListDto order(
      @NotNull UUID id,
      long startedMinutesAgo,
      long durationMinutes,
      @NotNull String ore,
      @NotNull String refined) {
    RefineryGoodDto good =
        new RefineryGoodDto(
            UUID.randomUUID(), material(ore), 2300, material(refined), 1840, 812, 0);
    return new RefineryOrderListDto(
        id,
        new UserReferenceDto(VIEWER, "greluc", null, "greluc", 0),
        new LocationDto(UUID.randomUUID(), "ARC-L1 Wide Forest Station", null, false, false, 1L),
        null,
        Instant.now().minus(startedMinutesAgo, ChronoUnit.MINUTES),
        durationMinutes,
        0d,
        0d,
        0d,
        0d,
        new RefiningMethodDto(UUID.randomUUID(), "Dinyx Solventation", null, null, 3, 1, 1),
        "OPEN",
        List.of(good),
        null,
        1L);
  }

  /**
   * The two open orders as the backend lists them by end: the ready one, then the running one.
   *
   * @return the page holding both
   */
  private static @NotNull PageResponse<RefineryOrderListDto> twoOpenOrders() {
    return new PageResponse<>(
        List.of(
            order(READY_ID, 120, 60, "Quantanium-Erz", "Quantanium"),
            order(RUNNING_ID, 10, 200, "Taranite-Erz", "Taranite")),
        0,
        50,
        2L,
        1,
        List.of());
  }

  /**
   * One order alone, as the backend answers a filtered request.
   *
   * @param order the order
   * @return the page holding it
   */
  private static @NotNull PageResponse<RefineryOrderListDto> onlyOrder(
      @NotNull RefineryOrderListDto order) {
    return new PageResponse<>(List.of(order), 0, 50, 1L, 1, List.of());
  }

  /** The list renders on the pattern: head, segment with counters, columns, ready row first. */
  @Test
  void rendersTheListPattern() throws Exception {
    String html = render(twoOpenOrders(), "");
    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Flotte &amp; Logistik<")
        .containsPattern("<h1>Raffinerie</h1>")
        .contains("data-testid=\"refinery-create-link\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
    assertThat(main).doesNotContainPattern("class=\"[^\"]*krtm-");
    assertThat(html.split("btn--cta", -1)).hasSize(2);
    assertThat(html)
        .contains("data-testid=\"segment-view-running\"")
        .contains("data-testid=\"segment-view-ready\"")
        .contains("data-testid=\"segment-view-completed\"")
        .contains("data-testid=\"segment-view-all\"")
        .containsPattern("name=\"view\" value=\"RUNNING\" checked=\"checked\"")
        .containsPattern("class=\"seg-count\">2<")
        .containsPattern("class=\"seg-count\">1<")
        .contains("data-testid=\"toolbar-search\"")
        .contains("id=\"refinery-only-mine\"");
    assertThat(html)
        .contains("class=\"data-table data-table--stack refinery-table\"")
        .contains(">Ausbeute<")
        .contains(">Fertig<")
        .doesNotContain(">Staffel<")
        .doesNotContain("colspan")
        .containsPattern(
            "class=\"row-link\"[^>]*href=\"/refinery-orders/00000000-0000-0000-0000-000000001042\"")
        .contains("Quantanium 18,4 SCU")
        .contains("#1042")
        .contains("Quantanium-Erz 2.300 Units")
        .contains("Dinyx Solventation")
        .contains("abholbereit seit 1 h")
        .contains("class=\"meter refinery-meter\"")
        .contains("meter__fill--success");
    assertThat(html.indexOf("#1042"))
        .as("the rows keep the backend's by-end order")
        .isLessThan(html.indexOf("#1051"));
    assertThat(html)
        .containsPattern(
            "refinery-row--ready\"[^>]*data-state=\"READY\"|data-state=\"READY\"[^>]*refinery-row--ready")
        .contains("href=\"/refinery-orders/00000000-0000-0000-0000-000000001042?store=open\"")
        .containsPattern(
            "href=\"/refinery-orders/00000000-0000-0000-0000-000000001051\\?store=open\"[^>]*hidden=\"hidden\"");
  }

  /**
   * The default segment is one backend page of the open orders by end, and every counter is a
   * one-row request; nothing loads a large page to filter in memory.
   */
  @Test
  void runningSegmentFetchesOnePageByEndAndCountsWithOneRowRequests() throws Exception {
    render(twoOpenOrders(), "");

    String running =
        "/api/v1/refinery-orders/all?page={page}&size={size}&sort=endsAt,asc"
            + "&status=OPEN,IN_PROGRESS";
    verify(backendApiClient).get(eq(running), anyTypeRef(), eq(0), eq(50));
    verify(backendApiClient).get(eq(running), anyTypeRef(), eq(0), eq(1));
    verify(backendApiClient).get(eq(running + "&ready=true"), anyTypeRef(), eq(0), eq(1));
    verify(backendApiClient)
        .get(
            eq(
                "/api/v1/refinery-orders/all?page={page}&size={size}&sort=startedAt,desc"
                    + "&status=COMPLETED"),
            anyTypeRef(),
            eq(0),
            eq(1));
    verify(backendApiClient)
        .get(
            eq(
                "/api/v1/refinery-orders/all?page={page}&size={size}&sort=startedAt,desc"
                    + "&status=OPEN,IN_PROGRESS,COMPLETED,CANCELED"),
            anyTypeRef(),
            eq(0),
            eq(1));
    verify(backendApiClient, never()).get(anyString(), anyTypeRef(), any(), eq(1000));
  }

  /** The ready segment asks the backend for the ready orders of the requested page and size. */
  @Test
  void readySegmentListsOnlyReadyOrders() throws Exception {
    String html = render(twoOpenOrders(), "view=READY&page=1&size=10");

    assertThat(html)
        .containsPattern("name=\"view\" value=\"READY\" checked=\"checked\"")
        .contains("#1042")
        .doesNotContain("#1051");
    verify(backendApiClient)
        .get(
            eq(
                "/api/v1/refinery-orders/all?page={page}&size={size}&sort=endsAt,asc"
                    + "&status=OPEN,IN_PROGRESS&ready=true"),
            anyTypeRef(),
            eq(1),
            eq(10));
  }

  /** The search is passed to the backend as {@code q} and the rows are its answer. */
  @Test
  void searchIsPassedToTheBackend() throws Exception {
    stubLists(twoOpenOrders());
    when(backendApiClient.get(contains("&q={q}"), anyTypeRef(), eq(0), eq(50), eq("taranite")))
        .thenReturn(onlyOrder(order(RUNNING_ID, 10, 200, "Taranite-Erz", "Taranite")));

    String html = perform("view=COMPLETED&onlyMine=true&q=taranite");

    assertThat(html).contains("#1051").doesNotContain("#1042");
    verify(backendApiClient)
        .get(
            eq(
                "/api/v1/refinery-orders/my-orders?page={page}&size={size}&sort=startedAt,desc"
                    + "&status=COMPLETED&q={q}"),
            anyTypeRef(),
            eq(0),
            eq(50),
            eq("taranite"));
  }

  /** A former status filter stays exact and is kept by the pagination links. */
  @Test
  void legacyStatusFilterStaysExact() throws Exception {
    String html = render(new PageResponse<>(List.of(), 0, 50, 0L, 0, List.of()), "status=CANCELED");

    assertThat(html).containsPattern("name=\"view\" value=\"ALL\" checked=\"checked\"");
    verify(backendApiClient, atLeastOnce())
        .get(contains("&status=CANCELED"), anyTypeRef(), any(Object[].class));
  }

  /** An empty segment shows the empty state, not a table. */
  @Test
  void rendersTheEmptyState() throws Exception {
    String html = render(new PageResponse<>(List.of(), 0, 1000, 0L, 0, List.of()), "");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Raffinerieaufträge")
        .doesNotContain("refinery-table")
        .doesNotContain("colspan");
  }
}
