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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * Renders the full {@code index} view (including {@code fragments/sidebar} and the transitively
 * included {@code fragments/toast}) end-to-end through MockMvc, so any breakage in the toast
 * fragment's SpEL expressions surfaces as a failed test rather than a 500 in production.
 *
 * <p>Regression context: {@code fragments/toast} previously called {@code
 * #strings.matches(param.error[0], '...')}, which throws {@code SpelEvaluationException: EL1004E:
 * Method matches(java.lang.String,java.lang.String) cannot be found on type
 * org.thymeleaf.expression.Strings}. Thymeleaf's {@code Strings} utility has no {@code matches}
 * method &mdash; {@code matches} is a native SpEL infix operator. The fix switched both branches to
 * {@code param.X[0] matches '...'}. The original crash happened on plain {@code GET /} for
 * anonymous users after a failed Keycloak callback (re)appended {@code ?error=...} to the URL; the
 * tests below cover exactly that path.
 *
 * <p>The pre-fix code reached the broken {@code Strings.matches} call only when {@code param.error
 * != null} (resp. {@code param.success != null}) due to SpEL's short-circuiting {@code and}, so the
 * regression cases here intentionally supply a matching query parameter.
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

    // Anonymous home() path: backendApiClient.get(searchUri, typeRef, isPublic=true) for the
    // next-7-days upcoming-missions search. Returning null is a valid "no upcoming missions"
    // response and keeps the template's empty-state branch simple.
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef(), anyBoolean()))
        .thenReturn(null);
  }

  @Test
  void home_ShouldRenderIndex_WithoutQueryParams() throws Exception {
    // Given: no toast-controlling query parameters
    // When: anonymous GET /
    // Then: index renders normally; the toast fragment's param-gated branches stay
    //       inactive, but the rest of fragments/toast (script + style block) still
    //       runs through Thymeleaf and SpEL.
    mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("index"));
  }

  /**
   * Direct regression: pre-fix this exact request crashed the template with {@code EL1004E: Method
   * matches(String,String) cannot be found on type org.thymeleaf.expression.Strings}. After the
   * fix, the SpEL infix {@code param.error[0] matches '...'} evaluates cleanly and the param-toast
   * div is emitted in the response body.
   */
  @Test
  void home_ShouldRenderIndex_WhenErrorParamMatchesKeyPattern() throws Exception {
    // Given: ?error= with a value that matches '^[A-Za-z][A-Za-z0-9._-]{0,79}$'
    // When: anonymous GET /
    // Then: 200, view "index", and the param-error toast div is in the HTML.
    mockMvc
        .perform(get("/").param("error", "notification.error.title"))
        .andExpect(status().isOk())
        .andExpect(view().name("index"))
        .andExpect(content().string(containsString(ERROR_TOAST_ID)));
  }

  /**
   * Same regression as the error variant above, but for the symmetric {@code ?success=} branch
   * (toast.html line 38). Both branches used the broken {@code #strings.matches} call and both must
   * now route through the SpEL {@code matches} operator.
   */
  @Test
  void home_ShouldRenderIndex_WhenSuccessParamMatchesKeyPattern() throws Exception {
    mockMvc
        .perform(get("/").param("success", "notification.success.title"))
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
    // Given: a value that violates the key pattern (contains spaces, starts with digit)
    // When: anonymous GET /
    // Then: 200, view "index", and the param-error toast div is absent.
    mockMvc
        .perform(get("/").param("error", "9 invalid value with spaces"))
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
        .perform(get("/").param("success", ""))
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
            0L);
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef(), anyBoolean()))
        .thenReturn(new PageResponse<>(List.of(mission), 0, 50, 1, 1, List.of()));

    mockMvc
        .perform(get("/"))
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
            // owningSquadron null → ownerless mission (mirrors the serialized DTO).
            null,
            null,
            0L);
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef(), anyBoolean()))
        .thenReturn(new PageResponse<>(List.of(mission), 0, 50, 1, 1, List.of()));

    mockMvc
        .perform(get("/"))
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
            0L);
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef(), anyBoolean()))
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
            0L);
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef(), anyBoolean()))
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
    // No Staffel on the /me record — the membership comes purely from /me/org-unit-ids.
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
            0L);
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef(), anyBoolean()))
        .thenReturn(new PageResponse<>(List.of(skMission), 0, 50, 1, 1, List.of()));

    mockMvc
        .perform(get("/").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Meine Einheit")));
  }
}
