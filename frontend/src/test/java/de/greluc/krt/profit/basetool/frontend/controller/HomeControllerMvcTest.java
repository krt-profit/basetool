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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
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
 * Renders the full {@code index} view, including the sidebar and toast fragments, through MockMvc
 * so a broken SpEL expression in the toast fragment fails a test instead of returning 500. Uses
 * {@code ?error=} and {@code ?success=} parameters to exercise the toast's {@code matches}
 * branches.
 */
@SpringBootTest
class HomeControllerMvcTest {

  private static final String ERROR_TOAST_ID = "errorNotificationParam";
  private static final String SUCCESS_TOAST_ID = "successNotificationParam";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef()))
        .thenReturn(null);
  }

  @Test
  void home_ShouldRenderIndex_WithoutQueryParams() throws Exception {
    mockMvc
        .perform(get("/").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(view().name("index"));
  }

  /**
   * The home page renders the overview pattern (REQ-UI-027): the greeting without a description,
   * the upcoming missions as row links with a date block and translated status, and the
   * notifications card; no hud-box and no call to action per mission.
   */
  @Test
  void home_ShouldRenderTheOverviewPattern() throws Exception {
    java.time.Instant start = java.time.Instant.now().plus(2, java.time.temporal.ChronoUnit.DAYS);
    MissionListDto mission =
        new MissionListDto(
            UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
            "Salvage-Lauf",
            null,
            null,
            "PLANNED",
            start.minus(30, java.time.temporal.ChronoUnit.MINUTES),
            start,
            null,
            null,
            null,
            false,
            null,
            null,
            "GrimHEX",
            6L,
            false,
            0L);
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(mission), 0, 50, 1, 1, List.of()));

    String html =
        mockMvc
            .perform(get("/").with(oidcLogin()).locale(java.util.Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.assertj.core.api.Assertions.assertThat(html)
        .contains("data-testid=\"home-head\"")
        .contains("data-testid=\"home-eyebrow\"")
        .doesNotContain("hud-box")
        .doesNotContain("mission-tile")
        .contains("data-testid=\"home-upcoming\"")
        .containsPattern(
            "class=\"home-mission\"[^>]*href=\"/missions/00000000-0000-0000-0000-0000000000a1\"")
        .contains("class=\"home-date\"")
        .contains(">GEPLANT<")
        .doesNotContain(">PLANNED<")
        .contains("GrimHEX")
        .contains("data-testid=\"home-notifications\"")
        .contains("Alle Einsätze →");
    org.assertj.core.api.Assertions.assertThat(
            html.substring(html.indexOf("<main"), html.indexOf("</main>")).split("btn--cta", -1))
        .hasSize(1);
  }

  /**
   * REQ-MISSION-012: a row the viewer is signed up for carries the „Angemeldet" chip, read from the
   * per-caller {@code signedUp} flag of the search; a row they are not signed up for carries none.
   */
  @Test
  void home_ShouldShowSignedUpChip_OnlyOnMissionsTheViewerIsSignedUpFor() throws Exception {
    MissionListDto joined = upcoming("Erzkonvoi-Eskorte", true);
    MissionListDto open = upcoming("Grenzpatrouille Sol", false);
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(joined, open), 0, 50, 2, 1, List.of()));

    String html =
        mockMvc
            .perform(get("/").with(oidcLogin()).locale(java.util.Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String[] rows = html.split("data-testid=\"home-mission\"", -1);
    org.assertj.core.api.Assertions.assertThat(rows).hasSize(3);
    org.assertj.core.api.Assertions.assertThat(rows[1])
        .contains("Erzkonvoi-Eskorte")
        .containsPattern("class=\"chip chip--success\"[^>]*data-testid=\"home-mission-signed-up\"")
        .contains(">Angemeldet<");
    org.assertj.core.api.Assertions.assertThat(rows[2])
        .contains("Grenzpatrouille Sol")
        .doesNotContain("home-mission-signed-up");
  }

  /** The „Angemeldet" chip is translated: an English viewer reads „Signed up". */
  @Test
  void home_ShouldTranslateSignedUpChip_ForAnEnglishViewer() throws Exception {
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(List.of(upcoming("Salvage-Lauf", true)), 0, 50, 1, 1, List.of()));

    mockMvc
        .perform(get("/").with(oidcLogin()).param("lang", "en"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(">Signed up<")));
  }

  /**
   * Builds a planned upcoming mission row starting tomorrow.
   *
   * @param name the mission name
   * @param signedUp whether the viewer is signed up for it
   * @return the list row as the search returns it
   */
  private static MissionListDto upcoming(String name, boolean signedUp) {
    java.time.Instant start = java.time.Instant.now().plus(1, java.time.temporal.ChronoUnit.DAYS);
    return new MissionListDto(
        UUID.randomUUID(),
        name,
        null,
        null,
        "PLANNED",
        null,
        start,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        1L,
        signedUp,
        0L);
  }

  /**
   * An {@code ?error=} value matching the key pattern renders the page with the parameter toast.
   */
  @Test
  void home_ShouldRenderIndex_WhenErrorParamMatchesKeyPattern() throws Exception {
    mockMvc
        .perform(get("/").with(oidcLogin()).param("error", "notification.error.title"))
        .andExpect(status().isOk())
        .andExpect(view().name("index"))
        .andExpect(content().string(containsString(ERROR_TOAST_ID)));
  }

  /**
   * A {@code ?success=} value matching the key pattern renders the page with the parameter toast.
   */
  @Test
  void home_ShouldRenderIndex_WhenSuccessParamMatchesKeyPattern() throws Exception {
    mockMvc
        .perform(get("/").with(oidcLogin()).param("success", "notification.success.title"))
        .andExpect(status().isOk())
        .andExpect(view().name("index"))
        .andExpect(content().string(containsString(SUCCESS_TOAST_ID)));
  }

  /**
   * The regex gate exists to prevent arbitrary translation-key enumeration via {@code
   * ?error=any.key}; a value that fails the pattern must simply skip the toast div (no crash, no
   * leakage). This test pins both halves of the contract: (a) the request does not 500, and (b) the
   * param-error toast is NOT rendered.
   */
  @Test
  void home_ShouldRenderIndex_WithoutParamErrorToast_WhenErrorParamFailsKeyPattern()
      throws Exception {
    mockMvc
        .perform(get("/").with(oidcLogin()).param("error", "9 invalid value with spaces"))
        .andExpect(status().isOk())
        .andExpect(view().name("index"))
        .andExpect(content().string(not(containsString(ERROR_TOAST_ID))));
  }

  /**
   * Empty {@code ?success=} satisfies {@code param.success != null} (the param is present, just
   * empty) but fails the key pattern (which requires a leading letter). Must not crash and must not
   * emit the success-param toast.
   */
  @Test
  void home_ShouldRenderIndex_WithoutParamSuccessToast_WhenSuccessParamIsEmpty() throws Exception {
    mockMvc
        .perform(get("/").with(oidcLogin()).param("success", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("index"))
        .andExpect(content().string(not(containsString(SUCCESS_TOAST_ID))));
  }

  /**
   * Each upcoming-mission tile surfaces the mission's owning org unit (mission-next-banner.md,
   * REQ-MISSION-012): an org-owned mission renders the org unit's name. Exercises the {@code
   * mission.owningSquadron.name} Thymeleaf expression over a typed {@link MissionListDto} returned
   * by the next-7-days search.
   */
  @Test
  void home_ShouldShowOwningOrgUnitName_WhenUpcomingMissionIsOrgOwned() throws Exception {
    MissionListDto mission =
        new MissionListDto(
            UUID.randomUUID(),
            "Test Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            new SquadronReferenceDto(UUID.randomUUID(), "Alpha Staffel", "ALF"),
            null,
            0L,
            false,
            0L);
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(mission), 0, 50, 1, 1, List.of()));

    mockMvc
        .perform(get("/").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Alpha Staffel")));
  }

  /**
   * For an ownerless (leadership) upcoming mission the tile falls back to the "Keine" label instead
   * of omitting the row, mirroring the Verwaltung read-only display.
   */
  @Test
  void home_ShouldShowOwnerlessLabel_WhenUpcomingMissionHasNoOrgUnit() throws Exception {
    MissionListDto mission =
        new MissionListDto(
            UUID.randomUUID(),
            "Leadership Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            0L,
            false,
            0L);
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(mission), 0, 50, 1, 1, List.of()));

    mockMvc
        .perform(get("/").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Keine")));
  }

  /**
   * REQ-MISSION-012: a tile whose owning org unit is one of the authenticated viewer's own Staffeln
   * carries the "Meine Einheit" chip. The viewer's Staffel id is fed through {@code
   * /api/v1/users/me} and matched against the upcoming mission's {@code owningSquadron.id} in the
   * template.
   */
  @Test
  void home_ShouldShowMyUnitChip_WhenUpcomingMissionIsOwnedByViewersStaffel() throws Exception {
    UUID staffelId = UUID.randomUUID();
    SquadronReferenceDto myStaffel = new SquadronReferenceDto(staffelId, "Adler Staffel", "ADL");
    UserDto me =
        new UserDto(
            UUID.randomUUID(),
            "tester",
            "Tester",
            "Tester",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            true,
            null,
            List.of(myStaffel),
            0L,
            null,
            null);
    when(backendApiClient.get(eq("/api/v1/users/me"), eq(UserDto.class))).thenReturn(me);

    MissionListDto ownMission =
        new MissionListDto(
            UUID.randomUUID(),
            "Erzkonvoi-Eskorte",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            myStaffel,
            null,
            0L,
            false,
            0L);
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(ownMission), 0, 50, 1, 1, List.of()));

    mockMvc
        .perform(get("/").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Meine Einheit")));
  }

  /**
   * REQ-MISSION-012: a tile owned by a foreign unit (not one of the viewer's Staffeln) does not get
   * the "Meine Einheit" chip, even for an authenticated viewer — the broad search scope surfaces
   * it, but the highlight is reserved for the viewer's own units.
   */
  @Test
  void home_ShouldNotShowMyUnitChip_WhenUpcomingMissionIsForeign() throws Exception {
    SquadronReferenceDto myStaffel =
        new SquadronReferenceDto(UUID.randomUUID(), "Adler Staffel", "ADL");
    UserDto me =
        new UserDto(
            UUID.randomUUID(),
            "tester",
            "Tester",
            "Tester",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            true,
            null,
            List.of(myStaffel),
            0L,
            null,
            null);
    when(backendApiClient.get(eq("/api/v1/users/me"), eq(UserDto.class))).thenReturn(me);

    MissionListDto foreignMission =
        new MissionListDto(
            UUID.randomUUID(),
            "Grenzpatrouille Sol",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            new SquadronReferenceDto(UUID.randomUUID(), "Falke Staffel", "FLK"),
            null,
            0L,
            false,
            0L);
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(foreignMission), 0, 50, 1, 1, List.of()));

    mockMvc
        .perform(get("/").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Meine Einheit"))));
  }

  /**
   * REQ-MISSION-012: the own-unit highlight covers every org-unit kind, not just Staffeln. A viewer
   * with no Staffel but a direct Spezialkommando membership (sourced from {@code
   * /api/v1/users/me/org-unit-ids}) still gets the "Meine Einheit" chip on a mission owned by that
   * SK. The same path covers a direct Bereich / Organisationsleitung membership.
   */
  @Test
  void home_ShouldShowMyUnitChip_WhenUpcomingMissionIsOwnedByViewersSpecialCommand()
      throws Exception {
    UUID specialCommandId = UUID.randomUUID();
    UserDto me =
        new UserDto(
            UUID.randomUUID(),
            "tester",
            "Tester",
            "Tester",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            true,
            null,
            List.of(),
            0L,
            null,
            null);
    when(backendApiClient.get(eq("/api/v1/users/me"), eq(UserDto.class))).thenReturn(me);
    when(backendApiClient.get(eq("/api/v1/users/me/org-unit-ids"), anyTypeRef()))
        .thenReturn(List.of(specialCommandId));

    MissionListDto skMission =
        new MissionListDto(
            UUID.randomUUID(),
            "Phantom-Operation",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            new SquadronReferenceDto(specialCommandId, "Phantom SK", "PHA"),
            null,
            0L,
            false,
            0L);
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(skMission), 0, 50, 1, 1, List.of()));

    mockMvc
        .perform(get("/").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Meine Einheit")));
  }

  /**
   * The landing page renders no data, calls no backend and creates no session (REQ-SEC-052).
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @org.springframework.security.test.context.support.WithAnonymousUser
  void anonymousRootRendersTheLandingPageWithoutDataOrSession() throws Exception {
    org.springframework.test.web.servlet.MvcResult result =
        mockMvc
            .perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("landing"))
            .andReturn();

    org.assertj.core.api.Assertions.assertThat(result.getRequest().getSession(false))
        .as("the landing page must not mint a session — REQ-SEC-025 counts every one of these")
        .isNull();
    org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeaders("Set-Cookie"))
        .as("nor set a session cookie")
        .noneMatch(header -> header.startsWith("__Host-SESSION=") || header.startsWith("SESSION="));
    org.mockito.Mockito.verifyNoInteractions(backendApiClient);
  }

  /**
   * The landing page shows a logged-out visitor no navigation, notification bell or org-unit
   * switcher; the sidebar's {@code sec:authorize} guards are the only protection on this public
   * page (REQ-SEC-052).
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @org.springframework.security.test.context.support.WithAnonymousUser
  void anonymousLandingPageShowsNoNavigation() throws Exception {
    String html =
        mockMvc
            .perform(get("/"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.assertj.core.api.Assertions.assertThat(html)
        .as("the notification bell is behind sec:authorize and must not render")
        .doesNotContain("id=\"notification-bell\"");
    org.assertj.core.api.Assertions.assertThat(html)
        .as("nor any of the tool's own destinations")
        .doesNotContain("/missions")
        .doesNotContain("/inventory")
        .doesNotContain("/orders")
        .doesNotContain("/hangar");
    org.assertj.core.api.Assertions.assertThat(html)
        .as("what it DOES carry: the two login entry points and the legal pages")
        .contains("/oauth2/authorization/keycloak")
        .contains("/impressum")
        .contains("/privacy")
        .contains("/licenses");
  }
}
