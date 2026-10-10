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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionListDto;
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
 * Renders the mission list on the list pattern (REQ-UI-027): page head with count and one primary
 * action, toolbar with search, period segment and filter popover, the row-link table with a
 * translated status, and the empty state instead of a bare paragraph.
 */
@SpringBootTest
class MissionsListPatternRenderTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders {@code /missions} in German with the given page from the backend.
   *
   * @param page the page the backend returns
   * @param query the query string, without {@code ?}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull PageResponse<MissionListDto> page, @NotNull String query)
      throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get("/missions?" + query).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * A mission row as the overview returns it.
   *
   * @param status the backend status string
   * @return the row
   */
  private static @NotNull MissionListDto mission(@NotNull String status) {
    Instant start = Instant.parse("2030-10-08T18:00:00Z");
    return new MissionListDto(
        UUID.fromString("00000000-0000-0000-0000-000000000042"),
        "Salvage-Lauf",
        null,
        null,
        status,
        start,
        start,
        null,
        null,
        null,
        false,
        null,
        null,
        "GrimHEX",
        6,
        false,
        1L);
  }

  /** The list renders on the pattern: head, toolbar, row link, translated status, foot. */
  @Test
  @WithMockUser(roles = "OFFICER")
  void rendersTheListPattern() throws Exception {
    String html =
        render(new PageResponse<>(List.of(mission("PLANNED")), 0, 20, 1L, 1, List.of()), "");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Einsatzplanung<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .contains("data-list-count-for=\"missions-results\"")
        .contains("data-testid=\"missions-create-link\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
    assertThat(html.split("btn--cta", -1)).hasSize(2);
    assertThat(html)
        .contains("data-testid=\"toolbar-search\"")
        .contains("data-testid=\"segment-period-upcoming\"")
        .containsPattern("name=\"period\" value=\"UPCOMING\" checked=\"checked\"")
        .contains("data-testid=\"missions-filter-toggle\"")
        .contains("data-filter-chips");
    assertThat(html)
        .contains("class=\"data-table data-table--stack\"")
        .contains("data-testid=\"mission-row\"")
        .containsPattern(
            "class=\"row-link\"[^>]*href=\"/missions/00000000-0000-0000-0000-000000000042\"")
        .contains("Treffpunkt: GrimHEX")
        .containsPattern("class=\"status-pill status-planned\">GEPLANT<")
        .doesNotContain(">PLANNED<")
        .contains("6 angemeldet")
        .contains("data-list-total=\"1\"")
        .contains("1–1 von 1");
  }

  /** An empty result renders the empty state and no table. */
  @Test
  @WithMockUser(roles = "OFFICER")
  void rendersTheEmptyState() throws Exception {
    String html =
        render(new PageResponse<>(List.<MissionListDto>of(), 0, 20, 0L, 0, List.of()), "");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Einsätze gefunden.")
        .doesNotContain("data-table--stack")
        .doesNotContain("data-list-total");
  }

  /** The past segment is selected from the URL. */
  @Test
  @WithMockUser(roles = "OFFICER")
  void selectsThePastSegmentFromTheUrl() throws Exception {
    String html =
        render(
            new PageResponse<>(List.of(mission("COMPLETED")), 0, 20, 1L, 1, List.of()),
            "period=PAST");

    assertThat(html)
        .containsPattern("name=\"period\" value=\"PAST\" checked=\"checked\"")
        .containsPattern("class=\"status-pill status-completed\">ABGESCHLOSSEN<");
  }
}
