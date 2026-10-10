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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.mission.model.MissionDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionFinanceTotalsDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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
 * Renders the mission detail on the detail pattern (REQ-UI-027): a page head with the back link,
 * the name and a translated status badge, one primary action in the head, editing as a mode behind
 * a ghost button instead of a tab, deleting in the overflow menu, the finances as KPI tiles over
 * lists, and no one-off migration classes.
 */
@SpringBootTest
class MissionDetailPatternRenderTest {

  private static final UUID MISSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000077");

  private static final Pattern MIGRATION_CLASS = Pattern.compile("class=\"[^\"]*krtm-");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders a page in German against the mocked backend.
   *
   * @param path the app-relative path
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
   * Stubs the mission read, the catalogs and an empty finance ledger.
   *
   * @param editable the value of both {@code canEdit} and {@code canManageManagers}
   */
  private void stubMission(boolean editable) {
    when(backendApiClient.get(eq("/api/v1/missions/{id}"), anyTypeRef(), eq(MISSION_ID)))
        .thenReturn(mission(editable));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    when(backendApiClient.get(
            eq("/api/v1/missions/{id}/finance-entries/summary"),
            eq(MissionFinanceTotalsDto.class),
            eq(MISSION_ID)))
        .thenReturn(
            new MissionFinanceTotalsDto(BigDecimal.ZERO, BigDecimal.ZERO, 0L, BigDecimal.ZERO, 0L));
    when(backendApiClient.get(
            eq("/api/v1/missions/{id}/finance-entries?size={size}"),
            anyTypeRef(),
            eq(MISSION_ID),
            eq(200)))
        .thenReturn(new PageResponse<>(List.of(), 0, 200, 0, 0, List.of()));
  }

  /**
   * An active mission with no participants, units, goals or steps.
   *
   * @param editable the value of both {@code canEdit} and {@code canManageManagers}
   * @return the mission
   */
  private static @NotNull MissionDto mission(boolean editable) {
    UserReferenceDto manager =
        new UserReferenceDto(UUID.randomUUID(), "manager", null, "Test Manager", 0);
    return new MissionDto(
        MISSION_ID,
        "Salvage-Lauf",
        null,
        null,
        "ACTIVE",
        null,
        null,
        null,
        null,
        null,
        false,
        Collections.emptySet(),
        Collections.emptyList(),
        Collections.emptyList(),
        null,
        null,
        Set.of(manager),
        editable,
        editable,
        1L,
        1L,
        1L,
        1L,
        0,
        0,
        null,
        null,
        null,
        null,
        0L,
        List.of(),
        0L,
        List.of(),
        0L,
        null,
        null);
  }

  /**
   * Cuts the part of the page between two markers.
   *
   * @param html the page
   * @param from the marker the part starts at
   * @param to the marker the part ends before
   * @return the part
   */
  private static @NotNull String between(
      @NotNull String html, @NotNull String from, @NotNull String to) {
    int start = html.indexOf(from);
    int end = html.indexOf(to, start);
    assertThat(start).as("marker %s", from).isNotNegative();
    assertThat(end).as("marker %s", to).isGreaterThan(start);
    return html.substring(start, end);
  }

  /**
   * Counts the occurrences of a substring.
   *
   * @param text the text
   * @param part the substring
   * @return how often it occurs
   */
  private static int count(@NotNull String text, @NotNull String part) {
    return text.split(Pattern.quote(part), -1).length - 1;
  }

  /** An editor sees the head, the edit mode behind a ghost button and the overflow delete. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheDetailPatternForAnEditor() throws Exception {
    stubMission(true);
    String html = render("/missions/" + MISSION_ID);

    assertThat(html)
        .contains("data-testid=\"page-head\"")
        .contains("data-testid=\"page-eyebrow\"")
        .containsPattern("<h1 id=\"mission-title\">Salvage-Lauf</h1>")
        .containsPattern("class=\"status-badge status-active\"[^>]*>AKTIV<")
        .doesNotContain(">ACTIVE<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("id=\"tab-verw\"")
        .doesNotContain("class=\"footacts\"")
        .doesNotContain("??mission.");
    assertThat(MIGRATION_CLASS.matcher(html).find()).as("no krtm-* class is left").isFalse();

    String actions = between(html, "class=\"page-actions\"", "class=\"facts-bar\"");
    assertThat(count(actions, "btn--cta")).as("one primary action in the head").isEqualTo(1);
    assertThat(actions)
        .contains("id=\"add-participant-btn\"")
        .contains("data-testid=\"mission-edit-toggle\"")
        .contains("data-testid=\"overflow-menu-toggle\"")
        .contains("delete-mission-btn");

    String edit = between(html, "id=\"pane-verw\"", "<dialog");
    assertThat(edit)
        .contains("id=\"mission-edit-title\"")
        .contains("data-testid=\"mission-edit-done\"")
        .contains("class=\"form-layout card\"")
        .contains("class=\"form-section\"")
        .contains("data-testid=\"segment-status-active\"")
        .contains("data-testid=\"mission-save\"");
    assertThat(count(edit, "btn--cta")).as("one primary action in the edit mode").isEqualTo(1);
  }

  /** The finances tab shows KPI tiles, one primary action and empty states, never a bare row. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheFinancesAsKpisOverLists() throws Exception {
    stubMission(true);
    String html = render("/missions/" + MISSION_ID);

    String finance = between(html, "id=\"pane-fin\"", "id=\"pane-verw\"");
    assertThat(finance)
        .contains("data-testid=\"mission-finance-kpis\"")
        .contains("class=\"kpi-total\"")
        .contains("data-testid=\"mission-finance-list\"")
        .contains("data-testid=\"mission-payout-list\"")
        .contains("data-testid=\"empty-state\"")
        .doesNotContain("sumstrip")
        .doesNotContain("colspan");
    assertThat(count(finance, "btn--cta")).as("one primary action in the tab").isEqualTo(1);
  }

  /**
   * The head's sign-up carries the {@code page-head-primary} test id and a refinery order of the
   * mission links its whole row through a {@code row-link} (REQ-UI-027).
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void pinsTheHeadPrimaryAndTheRefineryRowLink() throws Exception {
    stubMission(true);
    UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000001042");
    when(backendApiClient.get(
            eq("/api/v1/refinery-orders/mission/{id}"), anyTypeRef(), eq(MISSION_ID)))
        .thenReturn(
            List.of(
                new RefineryOrderListDto(
                    orderId,
                    null,
                    null,
                    null,
                    Instant.parse("2026-10-01T10:00:00Z"),
                    60L,
                    0d,
                    0d,
                    0d,
                    0d,
                    null,
                    "OPEN",
                    List.of(),
                    null,
                    1L)));
    String html = render("/missions/" + MISSION_ID);

    String actions = between(html, "class=\"page-actions\"", "class=\"facts-bar\"");
    assertThat(actions)
        .containsPattern("id=\"add-participant-btn\"[^>]*data-testid=\"page-head-primary\"");
    assertThat(html)
        .containsPattern(
            "class=\"row-link\" data-testid=\"row-link\" href=\"/refinery-orders/"
                + orderId
                + "\"");
  }

  /** A member who may not edit sees neither the edit toggle nor the edit mode nor the delete. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void hidesTheEditModeFromAReader() throws Exception {
    stubMission(false);
    String html = render("/missions/" + MISSION_ID);

    assertThat(html)
        .contains("id=\"add-participant-btn\"")
        .doesNotContain("data-testid=\"mission-edit-toggle\"")
        .doesNotContain("id=\"pane-verw\"")
        .doesNotContain("delete-mission-btn");
  }

  /** The create page uses the page head with its back link and the numbered form sections. */
  @Test
  @WithMockUser(roles = "OFFICER")
  void rendersTheCreatePageOnTheFormPattern() throws Exception {
    String html = render("/missions/new");

    assertThat(html)
        .contains("data-testid=\"page-head\"")
        .contains("data-testid=\"page-eyebrow\"")
        .contains("class=\"form-layout card\"")
        .contains("data-testid=\"mission-save\"")
        .contains("id=\"mission-create-objective-list\"")
        .doesNotContain("mission-head-sticky")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("name=\"actualStartTime\"");
    assertThat(MIGRATION_CLASS.matcher(html).find()).as("no krtm-* class is left").isFalse();
    String main = between(html, "class=\"page-wrapper mission-page\"", "<script");
    assertThat(count(main, "btn--cta")).as("one primary action on the page").isEqualTo(1);
  }
}
