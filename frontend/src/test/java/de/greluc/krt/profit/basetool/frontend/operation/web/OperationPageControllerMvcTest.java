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

package de.greluc.krt.profit.basetool.frontend.operation.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PayoutPreference;
import de.greluc.krt.profit.basetool.frontend.mission.model.FinanceType;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionFinanceSummaryDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationDto;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationFinanceSummaryDto;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationMissionFinanceDto;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationPayoutDto;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationPayoutStatusDto;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationPayoutSummaryDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that the operations index and detail templates render operation and mission status
 * values through the i18n bundle rather than as raw enum names.
 */
@SpringBootTest
class OperationPageControllerMvcTest {

  /** German {@code operation.section.refresh.error} text, as both DE bundles spell it. */
  private static final String SECTION_REFRESH_ERROR_DE =
      "Der Abschnitt konnte nicht aktualisiert werden.";

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

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationsList_rendersOperationStatusViaI18n_inGerman() throws Exception {
    OperationDto op =
        new OperationDto(
            UUID.randomUUID(), "Op Alpha", "First op", "PLANNED", null, 0L, null, null, null);
    PageResponse<OperationDto> page =
        new PageResponse<>(List.of(op), 0, 20, 1L, 1, List.of("createdAt,desc"));
    when(backendApiClient.get(
            startsWith("/api/v1/operations/search?"), anyTypeRef(), any(Object[].class)))
        .thenReturn(page);

    mockMvc
        .perform(get("/operations").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("GEPLANT")))
        .andExpect(content().string(containsString("status-pill")))
        .andExpect(content().string(containsString("status-planned")))
        .andExpect(content().string(not(containsString(">PLANNED<"))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationsList_rendersOperationStatusViaI18n_inEnglish() throws Exception {
    OperationDto op =
        new OperationDto(
            UUID.randomUUID(), "Op Alpha", "First op", "ACTIVE", null, 0L, null, null, null);
    PageResponse<OperationDto> page =
        new PageResponse<>(List.of(op), 0, 20, 1L, 1, List.of("createdAt,desc"));
    when(backendApiClient.get(
            startsWith("/api/v1/operations/search?"), anyTypeRef(), any(Object[].class)))
        .thenReturn(page);

    mockMvc
        .perform(get("/operations").locale(Locale.ENGLISH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ACTIVE")))
        .andExpect(content().string(containsString("status-active")));
  }

  /**
   * Renders {@code /operations} in German with the given page from the backend.
   *
   * @param page the page the backend returns
   * @param query the query string, without {@code ?}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private String renderList(PageResponse<OperationDto> page, String query) throws Exception {
    when(backendApiClient.get(
            startsWith("/api/v1/operations/search?"), anyTypeRef(), any(Object[].class)))
        .thenReturn(page);
    return mockMvc
        .perform(get("/operations?" + query).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * An operation row as the search returns it.
   *
   * @param status the backend status string
   * @param description the description, may be {@code null}
   * @return the row
   */
  private static OperationDto listedOperation(String status, String description) {
    return new OperationDto(
        UUID.fromString("00000000-0000-0000-0000-000000000077"),
        "Op Alpha",
        description,
        status,
        null,
        0L,
        Instant.parse("2030-10-08T18:00:00Z"),
        null,
        null);
  }

  /**
   * The list renders on pattern A: head with eyebrow, count and the one create action, toolbar,
   * row-link table with a translated status and the admin's row delete, list foot.
   */
  @Test
  @WithMockUser(roles = {"ADMIN", "MISSION_MANAGER"})
  void operationsList_rendersTheListPattern() throws Exception {
    String html =
        renderList(
            new PageResponse<>(
                List.of(listedOperation("PLANNED", "Erste Operation")),
                0,
                20,
                1L,
                1,
                List.of("createdAt,desc")),
            "");

    assertTrue(html.contains("class=\"page-head\""), "page head");
    assertTrue(
        Pattern.compile("class=\"page-eyebrow\"[^>]*>Einsatzplanung<").matcher(html).find(),
        "eyebrow names the navigation area");
    assertTrue(
        Pattern.compile("data-testid=\"page-head-count\"[^>]*>1<").matcher(html).find(),
        "count chip");
    assertTrue(html.contains("data-list-count-for=\"operations-results\""));
    assertFalse(html.contains("class=\"greeting"), "no greeting banner");
    assertFalse(html.contains("hud-box"), "no hud-box");
    String head =
        html.substring(html.indexOf("class=\"page-head\""), html.indexOf("operations-filter-form"));
    assertEquals(2, head.split("btn--cta", -1).length, "exactly one primary action in the head");
    assertTrue(head.contains("data-modal-id=\"create-operation-modal\""), "create opens the modal");
    assertTrue(html.contains("data-testid=\"toolbar-search\""));
    assertTrue(html.contains("id=\"operation-search\""));
    assertTrue(html.contains("data-testid=\"segment-period-upcoming\""));
    assertTrue(
        Pattern.compile("name=\"period\" value=\"UPCOMING\" checked=\"checked\"")
            .matcher(html)
            .find(),
        "upcoming is the default segment");
    assertTrue(html.contains("data-testid=\"operations-filter-toggle\""));
    assertTrue(html.contains("data-filter-chips"));
    assertTrue(html.contains("class=\"data-table data-table--stack operations-table\""));
    assertTrue(
        Pattern.compile(
                "class=\"row-link\"[^>]*href=\"/operations/00000000-0000-0000-0000-000000000077\"")
            .matcher(html)
            .find(),
        "the first column links the row");
    assertTrue(html.contains("Erste Operation"), "the description is the row's sub line");
    assertTrue(
        Pattern.compile("class=\"status-pill status-planned\">GEPLANT<").matcher(html).find(),
        "translated status");
    assertFalse(html.contains(">PLANNED<"), "no raw status");
    assertTrue(html.contains("data-trigger=\"operations-open-delete\""), "admin row delete");
    assertTrue(html.contains("id=\"delete-operation-form\""));
    assertTrue(html.contains("data-list-total=\"1\""));
  }

  /** An empty result renders the empty state and no table. */
  @Test
  @WithMockUser(roles = "OFFICER")
  void operationsList_rendersTheEmptyState() throws Exception {
    String html =
        renderList(new PageResponse<>(List.<OperationDto>of(), 0, 20, 0L, 0, List.of()), "");

    assertTrue(html.contains("data-testid=\"empty-state\""));
    assertTrue(html.contains("Keine Operationen gefunden."));
    assertFalse(html.contains("data-table--stack"));
    assertFalse(html.contains("data-list-total"));
    assertFalse(html.contains("data-trigger=\"operations-open-delete\""));
  }

  /** The past segment relays only finished statuses and is selected from the URL. */
  @Test
  @WithMockUser(roles = "OFFICER")
  void operationsList_pastPeriod_relaysOnlyFinishedStatusesAndSelectsTheSegment() throws Exception {
    String html =
        renderList(
            new PageResponse<>(
                List.of(listedOperation("COMPLETED", null)), 0, 20, 1L, 1, List.of()),
            "period=PAST");

    List<String> relayed = relayedSearchUris();
    assertEquals(1, relayed.size(), relayed.toString());
    assertTrue(relayed.get(0).endsWith("&status=COMPLETED&status=CANCELED&"), relayed.get(0));
    assertTrue(
        Pattern.compile("name=\"period\" value=\"PAST\" checked=\"checked\"").matcher(html).find());
    assertTrue(
        Pattern.compile("class=\"status-pill status-completed\">ABGESCHLOSSEN<")
            .matcher(html)
            .find());
  }

  /** {@code showPast=true} still means all statuses; an unknown period falls back to upcoming. */
  @Test
  @WithMockUser(roles = "OFFICER")
  void operationsList_legacyShowPast_meansAllAndUnknownPeriodFallsBack() throws Exception {
    when(backendApiClient.get(
            startsWith("/api/v1/operations/search?"), anyTypeRef(), any(Object[].class)))
        .thenReturn(new PageResponse<>(List.<OperationDto>of(), 0, 20, 0L, 0, List.of()));

    mockMvc
        .perform(get("/operations").param("showPast", "true").param("fragment", "results"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/operations").param("period", "SOMETIME").param("fragment", "results"))
        .andExpect(status().isOk());

    List<String> relayed = relayedSearchUris();
    assertEquals(2, relayed.size(), relayed.toString());
    assertTrue(
        relayed.get(0).endsWith("&status=PLANNED&status=ACTIVE&status=COMPLETED&status=CANCELED&"),
        relayed.get(0));
    assertTrue(relayed.get(1).endsWith("&status=PLANNED&status=ACTIVE&"), relayed.get(1));
  }

  /**
   * The operation-search URIs relayed to the backend so far, in call order.
   *
   * @return the relayed {@code /api/v1/operations/search} URI templates
   */
  private List<String> relayedSearchUris() {
    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    verify(backendApiClient, atLeastOnce())
        .get(uriCaptor.capture(), anyTypeRef(), any(Object[].class));
    return uriCaptor.getAllValues().stream()
        .filter(uri -> uri.startsWith("/api/v1/operations/search?"))
        .toList();
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationsList_passesMultiWordSearchAsUriVariable() throws Exception {
    when(backendApiClient.get(
            startsWith("/api/v1/operations/search?"), anyTypeRef(), any(Object[].class)))
        .thenReturn(new PageResponse<>(List.<OperationDto>of(), 0, 20, 0L, 0, List.of()));

    mockMvc
        .perform(get("/operations").param("search", "Widget Alpha").param("fragment", "results"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> termCaptor = ArgumentCaptor.captor();
    verify(backendApiClient)
        .get(uriCaptor.capture(), anyTypeRef(), termCaptor.capture(), eq(0), eq(20));
    assertTrue(uriCaptor.getValue().contains("query={query}"), uriCaptor.getValue());
    assertEquals("Widget Alpha", termCaptor.getValue());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationsList_passesUmlautSearchAsUriVariable_notFormEncoded() throws Exception {
    when(backendApiClient.get(
            startsWith("/api/v1/operations/search?"), anyTypeRef(), any(Object[].class)))
        .thenReturn(new PageResponse<>(List.<OperationDto>of(), 0, 20, 0L, 0, List.of()));

    String term = "Müller Größe";
    mockMvc
        .perform(get("/operations").param("search", term).param("fragment", "results"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> termCaptor = ArgumentCaptor.captor();
    verify(backendApiClient)
        .get(uriCaptor.capture(), anyTypeRef(), termCaptor.capture(), eq(0), eq(20));
    assertTrue(uriCaptor.getValue().contains("query={query}"), uriCaptor.getValue());
    assertEquals(term, termCaptor.getValue());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void operationDetail_readOnlyUser_seesNoEditDialogAndNoActions() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op Read", "ro", "PLANNED", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"operation-title\"")))
        .andExpect(content().string(not(containsString("id=\"operation-form\""))))
        .andExpect(content().string(not(containsString("data-testid=\"operation-edit\""))))
        .andExpect(content().string(not(containsString("class=\"page-actions\""))))
        .andExpect(content().string(not(containsString("data-trigger=\"operation-open-delete\""))));
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void operationDetail_missionManager_seesEditDialogButNoDelete() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op Edit", "rw", "PLANNED", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-testid=\"operation-edit\"")))
        .andExpect(content().string(containsString("id=\"edit-operation-modal\"")))
        .andExpect(content().string(containsString("id=\"operation-form\"")))
        .andExpect(content().string(containsString("form=\"operation-form\"")))
        .andExpect(content().string(not(containsString("data-trigger=\"operation-open-delete\""))));
  }

  /**
   * The detail renders the 2026-10 layout: back eyebrow, title with translated status badge, the
   * edit action and the admin's overflow delete, the four KPIs, four tabs without the edit tab,
   * result rows linking each mission and the payout card.
   */
  @Test
  @WithMockUser(roles = {"ADMIN", "MISSION_MANAGER"})
  void operationDetail_rendersTheDetailLayout() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID missionId = UUID.fromString("00000000-0000-0000-0000-000000000042");
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Ironclad", "", "ACTIVE", null, 3L, null, null, null));
    when(backendApiClient.get(
            eq("/api/v1/operations/{id}/finance-summary"),
            eq(OperationFinanceSummaryDto.class),
            eq(opId)))
        .thenReturn(
            new OperationFinanceSummaryDto(
                opId,
                new BigDecimal("1284500"),
                List.of(
                    new OperationMissionFinanceDto(
                        missionId, "Quantanium-Abbau", new BigDecimal("1284500"))),
                false));
    when(backendApiClient.get(
            eq("/api/v1/operations/{id}/payouts"), eq(OperationPayoutSummaryDto.class), eq(opId)))
        .thenReturn(
            new OperationPayoutSummaryDto(
                new BigDecimal("128450"),
                List.of(
                    payoutRow("Pilot Paid", PayoutPreference.PAYOUT, "600000", true),
                    payoutRow("Pilot Open", PayoutPreference.DONATE, "412880", false))));

    String html =
        mockMvc
            .perform(get("/operations/" + opId).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertTrue(
        Pattern.compile("class=\"page-eyebrow\" href=\"/operations\"").matcher(html).find(),
        "the eyebrow links back to the list");
    assertTrue(Pattern.compile("id=\"operation-title\">Ironclad<").matcher(html).find(), "title");
    assertTrue(
        Pattern.compile("class=\"status-badge status-active\"[^>]*>AKTIV<").matcher(html).find(),
        "translated status badge");
    assertFalse(html.contains(">ACTIVE<"), "no raw status");
    assertFalse(html.contains("hud-box"), "no hud-box");
    assertFalse(
        Pattern.compile("class=\"[^\"]*krtm-").matcher(html).find(), "no migrated one-off classes");
    String head =
        html.substring(html.indexOf("data-testid=\"page-head\""), html.indexOf("op-kpis"));
    assertFalse(head.contains("btn--cta"), "the head's edit action is a ghost button");
    assertTrue(head.contains("data-modal-id=\"edit-operation-modal\""), "edit opens the dialog");
    assertTrue(head.contains("data-trigger=\"operation-open-delete\""), "delete in the menu");
    assertTrue(head.contains("overflow-menu__item--danger"));
    assertEquals(5, html.split("class=\"kpi-total\"", -1).length, "four KPIs");
    assertTrue(html.contains("id=\"op-kpi-total\">1.284.500<"), "thousands separators");
    assertTrue(html.contains("id=\"op-kpi-donated\">128.450<"));
    assertTrue(html.contains("id=\"op-kpi-participants\">2<"));
    assertFalse(html.contains("optab-verw"), "the edit tab is gone");
    assertTrue(html.contains("id=\"optab-missions-count\""));
    assertTrue(
        Pattern.compile(
                "class=\"op-result-row\"[^>]*href=\"/missions/"
                    + "00000000-0000-0000-0000-000000000042\"")
            .matcher(html)
            .find(),
        "a result row links its mission");
    assertTrue(html.contains("data-krtm-width=\"100.0\""), "the largest result fills its bar");
    assertTrue(
        Pattern.compile("data-testid=\"op-payout-ratio\">1 / 2<").matcher(html).find(),
        "payout ratio");
    assertTrue(html.contains("412.880 aUEC"), "the open amount");
    assertTrue(html.contains("1 Person"), "the donation wish");
    assertTrue(html.contains("data-op-goto-tab=\"payout\""), "the card jumps to the payout tab");
    assertTrue(html.contains("data-testid=\"operation-payout-sum\""), "sum row");
    assertTrue(html.contains("id=\"op-payout-paid-count\">1 / 2<"));
    assertTrue(html.contains("class=\"chip chip--success\">Spenden<"), "preference as chip");
  }

  /**
   * One payout row for a render test.
   *
   * @param name participant name
   * @param preference payout preference
   * @param amount payout amount
   * @param paidOut whether the row is marked paid out
   * @return the row
   */
  private static OperationPayoutDto payoutRow(
      String name, PayoutPreference preference, String amount, boolean paidOut) {
    return new OperationPayoutDto(
        UUID.randomUUID().toString(),
        name,
        50.0,
        preference,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        new BigDecimal(amount),
        paidOut,
        null,
        null);
  }

  /** Without missions, results and participants every section shows its empty state. */
  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_rendersEmptyStates() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op Empty", "", "PLANNED", null, 0L, null, null, null));

    String html =
        mockMvc
            .perform(get("/operations/" + opId).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertTrue(html.contains("Noch kein Ergebnis"), "results empty state");
    assertTrue(html.contains("Keine Teilnehmenden"), "payout empty state");
    assertTrue(html.contains("Keine Einsätze gefunden."), "missions empty state");
    assertFalse(html.contains("data-table--stack"), "no empty tables");
    assertFalse(html.contains("data-op-goto-tab"), "no payout jump without participants");
  }

  private void stubDetailEndpoints(UUID opId, OperationDto operation) {
    when(backendApiClient.get(eq("/api/v1/operations/{id}"), eq(OperationDto.class), eq(opId)))
        .thenReturn(operation);
    when(backendApiClient.get(
            contains("/api/v1/missions/search?operationId={id}"),
            anyTypeRef(),
            eq(opId),
            any(),
            any()))
        .thenReturn(new PageResponse<>(List.<MissionListDto>of(), 0, 10, 0L, 0, List.of()));
    when(backendApiClient.get(
            eq("/api/v1/operations/{id}/finance-summary"),
            eq(OperationFinanceSummaryDto.class),
            eq(opId)))
        .thenReturn(new OperationFinanceSummaryDto(opId, BigDecimal.ZERO, List.of(), false));
    when(backendApiClient.get(
            eq("/api/v1/operations/{id}/payouts"), eq(OperationPayoutSummaryDto.class), eq(opId)))
        .thenReturn(new OperationPayoutSummaryDto(BigDecimal.ZERO, List.of()));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_translatesBothOperationAndMissionStatus() throws Exception {
    UUID opId = UUID.randomUUID();

    OperationDto operation =
        new OperationDto(opId, "Completed Op", "", "COMPLETED", null, 0L, null, null, null);
    when(backendApiClient.get(eq("/api/v1/operations/{id}"), eq(OperationDto.class), eq(opId)))
        .thenReturn(operation);

    MissionListDto mission =
        new MissionListDto(
            UUID.randomUUID(),
            "Aborted Mission",
            null,
            null,
            "CANCELLED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            false,
            0L);
    PageResponse<MissionListDto> missionsPage =
        new PageResponse<>(List.of(mission), 0, 10, 1L, 1, List.of("plannedStartTime,asc"));
    when(backendApiClient.get(
            contains("/api/v1/missions/search?operationId={id}"),
            anyTypeRef(),
            eq(opId),
            any(),
            any()))
        .thenReturn(missionsPage);

    when(backendApiClient.get(
            eq("/api/v1/operations/{id}/finance-summary"),
            eq(OperationFinanceSummaryDto.class),
            eq(opId)))
        .thenReturn(new OperationFinanceSummaryDto(opId, BigDecimal.ZERO, List.of(), false));
    when(backendApiClient.get(
            eq("/api/v1/operations/{id}/payouts"), eq(OperationPayoutSummaryDto.class), eq(opId)))
        .thenReturn(new OperationPayoutSummaryDto(BigDecimal.ZERO, List.of()));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ABGESCHLOSSEN")))
        .andExpect(content().string(containsString("ABGEBROCHEN")))
        .andExpect(content().string(not(containsString(">COMPLETED<"))))
        .andExpect(content().string(not(containsString(">CANCELLED<"))))
        .andExpect(content().string(containsString("status-cancelled")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentMissions_rendersOnlyMissionsFragment() throws Exception {
    UUID opId = UUID.randomUUID();
    OperationDto operation =
        new OperationDto(opId, "Op", "", "PLANNED", null, 0L, null, null, null);
    when(backendApiClient.get(eq("/api/v1/operations/{id}"), eq(OperationDto.class), eq(opId)))
        .thenReturn(operation);

    MissionListDto mission =
        new MissionListDto(
            UUID.randomUUID(),
            "Frag Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            false,
            0L);
    when(backendApiClient.get(
            contains("/api/v1/missions/search?operationId={id}"),
            anyTypeRef(),
            eq(opId),
            any(),
            any()))
        .thenReturn(
            new PageResponse<>(List.of(mission), 0, 10, 15L, 2, List.of("plannedStartTime,asc")));

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "missions").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Frag Mission")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(containsString("/operations/" + opId + "?page=1")))
        .andExpect(content().string(not(containsString("id=\"op-missions-results\""))))
        .andExpect(content().string(not(containsString("id=\"col-payout\""))));

    verify(backendApiClient, never())
        .get(
            eq("/api/v1/operations/{id}/finance-summary"),
            eq(OperationFinanceSummaryDto.class),
            eq(opId));
    verify(backendApiClient, never())
        .get(eq("/api/v1/operations/{id}/payouts"), eq(OperationPayoutSummaryDto.class), eq(opId));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentOverview_rendersOverviewSection() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op Frag", "", "PLANNED", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "overview").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(view().name("operation-detail :: overviewSection"))
        .andExpect(content().string(containsString("id=\"operation-head-meta\"")))
        .andExpect(content().string(containsString("data-kpi-participants=\"0\"")))
        .andExpect(content().string(containsString("data-kpi-total-negative=\"false\"")))
        .andExpect(content().string(not(containsString("data-testid=\"page-head\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentPayout_rendersPayoutSection_andSkipsFinanceAndMissions()
      throws Exception {
    UUID opId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/operations/{id}"), eq(OperationDto.class), eq(opId)))
        .thenReturn(new OperationDto(opId, "Op", "", "PLANNED", null, 0L, null, null, null));
    when(backendApiClient.get(
            eq("/api/v1/operations/{id}/payouts"), eq(OperationPayoutSummaryDto.class), eq(opId)))
        .thenReturn(new OperationPayoutSummaryDto(BigDecimal.ZERO, List.of()));

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "payout").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(view().name("operation-detail :: payoutSection"))
        .andExpect(content().string(not(containsString("id=\"pane-op-payout\""))));

    verify(backendApiClient, never())
        .get(
            eq("/api/v1/operations/{id}/finance-summary"),
            eq(OperationFinanceSummaryDto.class),
            eq(opId));
    verify(backendApiClient, never())
        .get(
            contains("/api/v1/missions/search?operationId={id}"),
            anyTypeRef(),
            eq(opId),
            any(),
            any());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentFinance_rendersFinanceSection_andSkipsOperationRead()
      throws Exception {
    UUID opId = UUID.randomUUID();
    when(backendApiClient.get(
            eq("/api/v1/operations/{id}/finance-summary"),
            eq(OperationFinanceSummaryDto.class),
            eq(opId)))
        .thenReturn(new OperationFinanceSummaryDto(opId, BigDecimal.ZERO, List.of(), false));
    when(backendApiClient.get(
            eq("/api/v1/operations/{id}/payouts"), eq(OperationPayoutSummaryDto.class), eq(opId)))
        .thenReturn(new OperationPayoutSummaryDto(BigDecimal.ZERO, List.of()));
    when(backendApiClient.get(
            contains("/api/v1/missions/search?operationId={id}"),
            anyTypeRef(),
            eq(opId),
            any(),
            any()))
        .thenReturn(new PageResponse<>(List.<MissionListDto>of(), 0, 10, 0L, 0, List.of()));

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "finance").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(view().name("operation-detail :: financeSection"))
        .andExpect(content().string(not(containsString("id=\"pane-op-fin\""))));

    verify(backendApiClient, never())
        .get(eq("/api/v1/operations/{id}"), eq(OperationDto.class), eq(opId));
    verify(backendApiClient, never())
        .get(eq("/api/v1/operations/{id}/payouts"), eq(OperationPayoutSummaryDto.class), eq(opId));
    verify(backendApiClient, never())
        .get(
            contains("/api/v1/missions/search?operationId={id}"),
            anyTypeRef(),
            eq(opId),
            any(),
            any());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentUnknown_rendersFragmentError() throws Exception {
    UUID opId = UUID.randomUUID();

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "bogus").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(view().name("operation-detail :: fragmentError"));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentBackendFailure_rendersFragmentError() throws Exception {
    UUID opId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/operations/{id}"), eq(OperationDto.class), eq(opId)))
        .thenThrow(new RuntimeException("backend down"));

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "payout").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(view().name("operation-detail :: fragmentError"))
        .andExpect(content().string(containsString("Abschnitt konnte nicht aktualisiert werden")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fullPage_doesNotRenderTheSectionRefreshError() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op Inert", "", "PLANNED", null, 0L, null, null, null));

    String body =
        mockMvc
            .perform(get("/operations/" + opId).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String renderable =
        body.replaceAll("(?s)<template[^>]*>.*?</template>", "")
            .replaceAll("(?s)<script[^>]*>.*?</script>", "");

    assertFalse(
        renderable.contains(SECTION_REFRESH_ERROR_DE),
        "the fragmentError paragraph must not render on the full operation-detail page");
    assertTrue(
        body.contains(SECTION_REFRESH_ERROR_DE),
        "the fragmentError paragraph must still be present (inert) in the page source");
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentUnknown_stillRendersTheSectionRefreshError() throws Exception {
    UUID opId = UUID.randomUUID();

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "bogus").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(view().name("operation-detail :: fragmentError"))
        .andExpect(content().string(containsString(SECTION_REFRESH_ERROR_DE)))
        .andExpect(content().string(not(containsString("<template"))))
        .andExpect(content().string(not(containsString("id=\"operation-form\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationMissionFinance_rendersEntryBreakdownFragment() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    MissionFinanceEntryDto entry =
        new MissionFinanceEntryDto(
            UUID.randomUUID(),
            missionId,
            null,
            "Wave5DetailNote",
            FinanceType.INCOME,
            new BigDecimal("500"),
            0L);
    when(backendApiClient.get(
            eq("/api/v1/operations/{id}/finances/{missionId}"),
            eq(MissionFinanceSummaryDto.class),
            eq(opId),
            eq(missionId)))
        .thenReturn(
            new MissionFinanceSummaryDto(
                missionId, "Mission A", new BigDecimal("500"), List.of(entry), List.of()));

    mockMvc
        .perform(get("/operations/" + opId + "/finance/" + missionId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Wave5DetailNote")))
        .andExpect(content().string(not(containsString("id=\"pane-op-fin\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationMissionFinance_backendFailure_rendersErrorFragment() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(
            eq("/api/v1/operations/{id}/finances/{missionId}"),
            eq(MissionFinanceSummaryDto.class),
            eq(opId),
            eq(missionId)))
        .thenThrow(new RuntimeException("backend down"));

    mockMvc
        .perform(get("/operations/" + opId + "/finance/" + missionId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Details konnten nicht geladen werden")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_rendersDonationTotals_whenParticipantsDonate() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op", "", "COMPLETED", null, 0L, null, null, Boolean.FALSE));
    OperationPayoutDto donor =
        new OperationPayoutDto(
            UUID.randomUUID().toString(),
            "Donor Dan",
            50.0,
            PayoutPreference.DONATE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("350.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            false,
            null,
            null);
    when(backendApiClient.get(
            eq("/api/v1/operations/{id}/payouts"), eq(OperationPayoutSummaryDto.class), eq(opId)))
        .thenReturn(new OperationPayoutSummaryDto(new BigDecimal("350.00"), List.of(donor)));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Spenden gesamt")))
        .andExpect(content().string(containsString("Davon gespendet")))
        .andExpect(content().string(containsString("gespendet")))
        .andExpect(content().string(containsString("350")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_rendersPreliminaryWarning_whenBackendReportsUnfinishedMissions()
      throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId,
        new OperationDto(opId, "Ongoing Op", "", "ACTIVE", null, 0L, null, null, Boolean.TRUE));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "class=\"alert alert-info\" role=\"status\""
                            + " data-testid=\"operation-payout-preliminary\"")))
        .andExpect(content().string(containsString("Vorläufige Werte")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_hidesPreliminaryWarning_whenBackendReportsAllMissionsFinished()
      throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId,
        new OperationDto(opId, "Closed Op", "", "COMPLETED", null, 0L, null, null, Boolean.FALSE));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("operation-payout-preliminary"))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_hidesPreliminaryWarning_whenBackendOmitsFlag() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Unknown Op", "", "PLANNED", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("operation-payout-preliminary"))));
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void updatePayoutStatus_missionManager_isForbiddenFromSetting_paidOutFalse() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/operations/" + opId + "/payouts/paid-out")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantKey\":\"" + participantId + "\",\"paidOut\":false}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void updatePayoutStatus_officer_canClear_paidOutFalse() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    when(backendApiClient.put(
            eq("/api/v1/operations/{id}/payouts/paid-out"),
            any(),
            eq(OperationPayoutStatusDto.class),
            eq(opId)))
        .thenReturn(new OperationPayoutStatusDto(participantId.toString(), false, null, null));

    mockMvc
        .perform(
            post("/operations/" + opId + "/payouts/paid-out")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantKey\":\"" + participantId + "\",\"paidOut\":false}"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void updatePayoutStatus_missionManager_canSet_paidOutTrue() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    when(backendApiClient.put(
            eq("/api/v1/operations/{id}/payouts/paid-out"),
            any(),
            eq(OperationPayoutStatusDto.class),
            eq(opId)))
        .thenReturn(new OperationPayoutStatusDto(participantId.toString(), true, null, null));

    mockMvc
        .perform(
            post("/operations/" + opId + "/payouts/paid-out")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantKey\":\"" + participantId + "\",\"paidOut\":true}"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void updatePayoutStatus_mapsBackend409ToConflict_notServerError() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    when(backendApiClient.put(
            eq("/api/v1/operations/{id}/payouts/paid-out"),
            any(),
            eq(OperationPayoutStatusDto.class),
            eq(opId)))
        .thenThrow(new BackendServiceException("payout toggle race", null, 409));

    mockMvc
        .perform(
            post("/operations/" + opId + "/payouts/paid-out")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantKey\":\"" + participantId + "\",\"paidOut\":true}"))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void updateOperation_classicForm_maps409ToOptimisticLockingFlash() throws Exception {
    UUID opId = UUID.randomUUID();
    when(backendApiClient.put(eq("/api/v1/operations/{id}"), any(), eq(Void.class), eq(opId)))
        .thenThrow(new BackendServiceException("stale operation version", null, 409));

    mockMvc
        .perform(
            post("/operations/" + opId + "/update")
                .with(csrf())
                .param("name", "Op")
                .param("status", "ACTIVE")
                .param("version", "3"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/operations"))
        .andExpect(flash().attribute("errorMessage", "error.optimistic.locking"));
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void updateOperation_classicForm_mapsOtherErrorsToGenericFlash() throws Exception {
    UUID opId = UUID.randomUUID();
    when(backendApiClient.put(eq("/api/v1/operations/{id}"), any(), eq(Void.class), eq(opId)))
        .thenThrow(new BackendServiceException("backend down", null, 500));

    mockMvc
        .perform(
            post("/operations/" + opId + "/update")
                .with(csrf())
                .param("name", "Op")
                .param("status", "ACTIVE")
                .param("version", "3"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/operations"))
        .andExpect(flash().attribute("errorMessage", "operation.update.error"));
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void operationDetail_missionManager_rendersCanUnsetPaidOutFalse() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op", "", "ACTIVE", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-can-unset-paid-out=\"false\"")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_officer_rendersCanUnsetPaidOutTrue() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op", "", "ACTIVE", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-can-unset-paid-out=\"true\"")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void operationDetail_admin_rendersCanUnsetPaidOutTrue() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op", "", "ACTIVE", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-can-unset-paid-out=\"true\"")));
  }
}
