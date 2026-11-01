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

import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipReadinessDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionListDto;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Renders the bodies of the next-mission and hangar-readiness widgets through MockMvc (REQ-UI-034):
 * the next mission as one row or its empty state, and the fitted count of the hangar with the
 * member's own live-sync room.
 */
@SpringBootTest
class DashboardSummaryWidgetsMvcTest {

  private static final String NEXT_MISSION = "/dashboard/widgets/next-mission";
  private static final String HANGAR = "/dashboard/widgets/hangar-readiness";
  private static final String NEXT_PATH = "/api/v1/missions/next/summary?limit=";
  private static final String READINESS_PATH = "/api/v1/hangar/my-ships/readiness";
  private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

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
    return mockMvc
        .perform(
            get(path)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin().idToken(token -> token.subject(MEMBER.toString())))
                .locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static MissionListDto mission(boolean signedUp, SquadronReferenceDto unit) {
    return mission("00000000-0000-0000-0000-0000000000a1", "Erzkonvoi", signedUp, unit);
  }

  private static MissionListDto mission(
      String id, String name, boolean signedUp, SquadronReferenceDto unit) {
    Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
    return new MissionListDto(
        UUID.fromString(id),
        name,
        null,
        null,
        "PLANNED",
        start.minus(30, ChronoUnit.MINUTES),
        start,
        null,
        null,
        null,
        false,
        null,
        unit,
        "GrimHEX",
        3L,
        signedUp,
        0L);
  }

  /** The next missions are rows linking to the missions, with the signed-up chip, in read order. */
  @Test
  void nextMission_rendersTheRowsOfTheSummaryRead() throws Exception {
    when(backendApiClient.get(eq(NEXT_PATH + 5), anyTypeRef()))
        .thenReturn(
            List.of(
                mission(true, null),
                mission("00000000-0000-0000-0000-0000000000a2", "Bergungsflug", false, null)));

    String html = render(NEXT_MISSION);

    assertThat(html)
        .contains("data-widget-content")
        .containsPattern(
            "class=\"home-mission\"[^>]*href=\"/missions/00000000-0000-0000-0000-0000000000a1\"")
        .contains("Erzkonvoi")
        .contains(">GEPLANT<")
        .contains("data-testid=\"home-mission-signed-up\"")
        .contains("Alle Einsätze →");
    assertThat(html.split("data-testid=\"home-mission\"", -1)).hasSize(3);
    assertThat(html.indexOf("Erzkonvoi")).isLessThan(html.indexOf("Bergungsflug"));
    assertThat(html.split("data-testid=\"home-mission-signed-up\"", -1)).hasSize(2);
  }

  /** The rows option becomes the limit of the summary read; an unoffered value falls back to 5. */
  @Test
  void nextMission_asksTheBackendForTheRowsOption() throws Exception {
    when(backendApiClient.get(eq(NEXT_PATH + 3), anyTypeRef())).thenReturn(List.of());
    when(backendApiClient.get(eq(NEXT_PATH + 5), anyTypeRef())).thenReturn(List.of());

    render(NEXT_MISSION + "?rows=3");
    render(NEXT_MISSION + "?rows=4");

    verify(backendApiClient).get(eq(NEXT_PATH + 3), anyTypeRef());
    verify(backendApiClient).get(eq(NEXT_PATH + 5), anyTypeRef());
  }

  /** Without an upcoming mission the widget says so instead of showing a row. */
  @Test
  void nextMission_withoutAMissionShowsTheEmptyState() throws Exception {
    when(backendApiClient.get(eq(NEXT_PATH + 5), anyTypeRef())).thenReturn(List.of());

    String html = render(NEXT_MISSION);

    assertThat(html)
        .contains("data-testid=\"dashboard-next-mission-empty\"")
        .contains("Kein Einsatz geplant.")
        .doesNotContain("data-testid=\"home-mission\"");
  }

  /** The own-unit chip follows the caller's direct memberships, as in the upcoming-days rows. */
  @Test
  void nextMission_flagsTheMissionOfTheCallersOwnUnit() throws Exception {
    UUID unit = UUID.randomUUID();
    when(backendApiClient.get(eq(NEXT_PATH + 5), anyTypeRef()))
        .thenReturn(List.of(mission(false, new SquadronReferenceDto(unit, "Alpha", "ALF"))));
    when(backendApiClient.get(eq("/api/v1/org-units/me/ids"), anyTypeRef()))
        .thenReturn(List.of(unit));

    String html = render(NEXT_MISSION);

    assertThat(html).contains("Alpha").contains("Meine Einheit");
  }

  /** The hangar widget shows the fitted ships against the total and names the member's room. */
  @Test
  void hangarReadiness_showsTheFittedAndTotalCountsAndTheOwnRoom() throws Exception {
    when(backendApiClient.get(eq(READINESS_PATH), eq(ShipReadinessDto.class)))
        .thenReturn(new ShipReadinessDto(13, 9));

    String html = render(HANGAR);

    assertThat(html)
        .containsPattern("data-testid=\"dashboard-hangar-fitted\"[^>]*>9<")
        .contains("von 13 Schiffen einsatzbereit")
        .contains("data-live-sync-topic=\"hangar:" + MEMBER + "\"")
        .contains("href=\"/hangar\"")
        .doesNotContain("dashboard-hangar-empty");
  }

  /** A member without ships sees the empty state and no count. */
  @Test
  void hangarReadiness_withoutShipsShowsTheEmptyState() throws Exception {
    when(backendApiClient.get(eq(READINESS_PATH), eq(ShipReadinessDto.class)))
        .thenReturn(new ShipReadinessDto(0, 0));

    String html = render(HANGAR);

    assertThat(html)
        .contains("data-testid=\"dashboard-hangar-empty\"")
        .contains("Dein Hangar ist leer.")
        .doesNotContain("dashboard-hangar-fitted");
  }

  /** The English bundle carries the summary. */
  @Test
  void hangarReadiness_isTranslated() throws Exception {
    when(backendApiClient.get(eq(READINESS_PATH), eq(ShipReadinessDto.class)))
        .thenReturn(new ShipReadinessDto(4, 1));

    String html =
        mockMvc
            .perform(
                get(HANGAR)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .with(oidcLogin())
                    .param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("of 4 ships ready for action");
  }
}
