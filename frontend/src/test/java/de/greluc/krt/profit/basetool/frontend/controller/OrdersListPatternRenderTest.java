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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
 * Renders the order list on the list pattern (REQ-UI-027): page head with count and one primary
 * action, the scope segment and the filter popover, the row-link table with a translated status,
 * and the empty state instead of a {@code colspan} row.
 */
@SpringBootTest
class OrdersListPatternRenderTest {

  private static final String ORDER_ID = "00000000-0000-0000-0000-000000000042";

  private static final String QUEUE_URL =
      "/api/v1/orders?page=0&size=100&sort=priority,asc&status=OPEN,IN_PROGRESS";

  private static final String REQUESTED_URL =
      "/api/v1/orders/requested?page=0&size=100&sort=priority,asc&status=OPEN,IN_PROGRESS";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders {@code /orders} in German for a caller with the given capabilities.
   *
   * @param canViewJobOrders whether the caller may browse the queue
   * @param query the query string, without {@code ?}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(boolean canViewJobOrders, @NotNull String query) throws Exception {
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.capabilities(true, canViewJobOrders, true));
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get("/orders?" + query).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * A one-order page as the backend returns it.
   *
   * @param status the backend status string
   * @return the page
   */
  private static @NotNull PageResponse<JobOrderDto> onePage(@NotNull String status) {
    JobOrderDto order =
        new JobOrderDto(
            UUID.fromString(ORDER_ID),
            42,
            new SquadronReferenceDto(UUID.randomUUID(), "Iridium Squadron", "IRI"),
            new SquadronReferenceDto(UUID.randomUUID(), "Nova Squadron", "NOV"),
            "Tester",
            null,
            3,
            status,
            "MATERIAL",
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);
    return new PageResponse<>(List.of(order), 0, 100, 1L, 1, List.of());
  }

  /** The list renders on the pattern: head, scope segment, popover, row link, status, foot. */
  @Test
  @WithMockUser
  void rendersTheListPattern() throws Exception {
    when(backendApiClient.get(eq(QUEUE_URL), anyTypeRef())).thenReturn(onePage("OPEN"));

    String html = render(true, "");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Flotte &amp; Logistik<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .contains("data-list-count-for=\"orders-results\"")
        .contains("data-testid=\"orders-create-link\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain(">Filtern<");
    assertThat(html.split("btn--cta", -1)).hasSize(2);
    assertThat(html)
        .contains("data-testid=\"segment-scope-mine\"")
        .containsPattern("name=\"scope\" value=\"ALL\" checked=\"checked\"")
        .contains("data-testid=\"orders-filter-toggle\"")
        .contains("id=\"squadronFilterContainer\"")
        .contains("data-filter-chips");
    assertThat(html)
        .contains("class=\"data-table data-table--stack orders-table\"")
        .contains("data-testid=\"order-row\"")
        .containsPattern("class=\"row-link\"[^>]*href=\"/orders/" + ORDER_ID + "\"")
        .containsPattern("class=\"status-pill status-open\">Offen<")
        .doesNotContain(">OPEN<")
        .doesNotContain(">Details<")
        .contains("data-list-total=\"1\"")
        .contains("1–1 von 1");
  }

  /** An empty result renders the empty state and no table. */
  @Test
  @WithMockUser
  void rendersTheEmptyState() throws Exception {
    when(backendApiClient.get(eq(QUEUE_URL), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.<JobOrderDto>of(), 0, 100, 0L, 0, List.of()));

    String html = render(true, "");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Aufträge vorhanden")
        .doesNotContain("data-table--stack")
        .doesNotContain("colspan=\"5\"");
  }

  /** The own-orders segment reads the requester list and hides the squadron filter. */
  @Test
  @WithMockUser
  void mineScopeReadsTheRequestedList() throws Exception {
    when(backendApiClient.get(contains("/api/v1/orders/requested?"), anyTypeRef()))
        .thenReturn(onePage("IN_PROGRESS"));

    String html = render(true, "scope=MINE");

    verify(backendApiClient).get(eq(REQUESTED_URL), anyTypeRef());
    assertThat(html)
        .containsPattern("name=\"scope\" value=\"MINE\" checked=\"checked\"")
        .containsPattern("id=\"ordersSquadronField\"[^>]*hidden")
        .containsPattern("class=\"status-pill status-in_progress\">In Bearbeitung<")
        .doesNotContain(">Fortschritt<");
  }

  /**
   * The to-process segment reads the queue of the caller's own units, hides the squadron filter and
   * keeps the scope in the pagination links (REQ-ORDERS-040).
   */
  @Test
  @WithMockUser
  void toProcessScopeReadsTheQueueOfTheCallersUnits() throws Exception {
    when(backendApiClient.get(contains("/api/v1/orders?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(onePage("OPEN").content(), 0, 100, 300L, 3, List.of()));

    String html = render(true, "scope=TO_PROCESS&squadronId=" + UUID.randomUUID());

    verify(backendApiClient).get(eq(QUEUE_URL + "&toProcess=true"), anyTypeRef());
    assertThat(html)
        .contains("data-testid=\"segment-scope-mine\"")
        .contains("data-testid=\"segment-scope-to_process\"")
        .contains("data-testid=\"segment-scope-all\"")
        .containsPattern("name=\"scope\" value=\"TO_PROCESS\" checked=\"checked\"")
        .contains(">Zu bearbeiten<")
        .containsPattern("id=\"ordersSquadronField\"[^>]*hidden")
        .contains("/orders?scope=TO_PROCESS&amp;status=OPEN&amp;status=IN_PROGRESS")
        .doesNotContain("squadronId=")
        .contains("data-testid=\"order-row\"");
  }

  /** The default scope reads the full queue without the processing flag. */
  @Test
  @WithMockUser
  void allScopeSendsNoProcessingFlag() throws Exception {
    when(backendApiClient.get(contains("/api/v1/orders?"), anyTypeRef()))
        .thenReturn(onePage("OPEN"));

    String html = render(true, "scope=ALL");

    verify(backendApiClient).get(eq(QUEUE_URL), anyTypeRef());
    assertThat(html)
        .containsPattern("name=\"scope\" value=\"ALL\" checked=\"checked\"")
        .doesNotContainPattern("id=\"ordersSquadronField\"[^>]*hidden");
  }

  /** A requester without the queue sees their own orders and no scope segment. */
  @Test
  @WithMockUser
  void requesterSeesNoScopeSegment() throws Exception {
    when(backendApiClient.get(contains("/api/v1/orders/requested?"), anyTypeRef()))
        .thenReturn(onePage("OPEN"));

    String html = render(false, "scope=ALL");

    verify(backendApiClient).get(eq(REQUESTED_URL), anyTypeRef());
    assertThat(html)
        .contains("data-testid=\"order-row\"")
        .doesNotContain("data-testid=\"segment-scope-mine\"")
        .doesNotContain("data-testid=\"segment-scope-to_process\"")
        .doesNotContain("id=\"squadronFilterContainer\"");
  }

  /** A requester cannot reach the to-process queue through the URL. */
  @Test
  @WithMockUser
  void requesterAskingForToProcessStillReadsTheirOwnOrders() throws Exception {
    when(backendApiClient.get(contains("/api/v1/orders/requested?"), anyTypeRef()))
        .thenReturn(onePage("OPEN"));

    render(false, "scope=TO_PROCESS");

    verify(backendApiClient).get(eq(REQUESTED_URL), anyTypeRef());
  }
}
