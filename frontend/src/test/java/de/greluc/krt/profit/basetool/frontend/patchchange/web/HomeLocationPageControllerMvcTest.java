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

package de.greluc.krt.profit.basetool.frontend.patchchange.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.ChooseHomeLocationRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationOptionDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationPoolRowsDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationPreviewDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationPromptDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.MemberRelocationState;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.RelocationResultDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC test of {@link HomeLocationPageController} (REQ-PATCH-020, REQ-PATCH-022): a pending member
 * sees the curated home locations and what would move, a member with nothing to choose is sent into
 * the tool, and the final choice reaches the backend, frees the gate's cached verdict and relays a
 * refusal with the backend's localised detail.
 */
@SpringBootTest
class HomeLocationPageControllerMvcTest {

  private static final String PROMPT = "/api/v1/patch-changes/me/home-location";
  private static final String PAGE = "/home-location";

  /** The session attribute {@code HomeLocationGateFilter} caches its "may proceed" verdict in. */
  private static final String GATE_VERDICT = "krt.homeLocation.checkedAt";

  private static final UUID PATCH_CHANGE = UUID.fromString("7a0c7a0c-0000-4000-8000-0000000000a4");
  private static final UUID PORT_TRESSLER = UUID.fromString("10c4710c-0000-4000-8000-000000000009");
  private static final UUID ORG_UNIT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant LIVE_AT = Instant.parse("2026-11-14T20:00:00Z");

  private static final HomeLocationOptionDto LORVILLE =
      new HomeLocationOptionDto(
          UUID.fromString("10c4710c-0000-4000-8000-000000000001"), "Lorville", "Stanton");
  private static final HomeLocationOptionDto LEVSKI =
      new HomeLocationOptionDto(
          UUID.fromString("10c4710c-0000-4000-8000-000000000002"), "Levski", "Nyx");

  private static final String CHOICE_JSON = "{\"locationId\":\"" + LORVILLE.id() + "\"}";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * A pending prompt of patch 4.4 with rows and a ship at Port Tressler, rows already at Lorville
   * and a ship without a location.
   *
   * @param locations the curated home locations on offer
   * @return the prompt
   */
  private static HomeLocationPromptDto pending(List<HomeLocationOptionDto> locations) {
    return new HomeLocationPromptDto(
        MemberRelocationState.PENDING,
        PATCH_CHANGE,
        "4.4",
        LIVE_AT,
        null,
        null,
        locations,
        List.of(
            new HomeLocationPreviewDto(
                PORT_TRESSLER,
                "Port Tressler",
                1284L,
                List.of(new HomeLocationPoolRowsDto(ORG_UNIT, "Staffel Profit", 4L)),
                1L),
            new HomeLocationPreviewDto(LORVILLE.id(), "Lorville", 7L, List.of(), 0L),
            new HomeLocationPreviewDto(null, null, 0L, List.of(), 1L)));
  }

  /**
   * A backend refusal as the backend client raises it.
   *
   * @param status the HTTP status
   * @param code the problem code
   * @param detail the backend's localised detail
   * @return the failure
   */
  private static BackendServiceException refusal(int status, String code, String detail) {
    return new BackendServiceException("refused", null, status, code, null, List.of(), detail);
  }

  /**
   * Renders the page for the signed-in member and returns its markup.
   *
   * @return the rendered page
   * @throws Exception if the request fails
   */
  private String renderPage() throws Exception {
    return mockMvc
        .perform(get(PAGE))
        .andExpect(status().isOk())
        .andExpect(view().name("patchchange/home-location"))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @WithMockUser
  void aPendingMemberSeesTheCuratedLocationsAndWhatMoves() throws Exception {
    when(backendApiClient.get(PROMPT, HomeLocationPromptDto.class))
        .thenReturn(pending(List.of(LORVILLE, LEVSKI)));

    assertThat(renderPage())
        .contains("Patch 4.4: Neue Home Location")
        .contains("Star Citizen hat mit Patch 4.4 alles an eine neue Residenz geliefert.")
        .containsPattern("value=\"" + LORVILLE.id() + "\"[^>]*data-location-name=\"Lorville\"")
        .containsPattern("value=\"" + LEVSKI.id() + "\"[^>]*data-location-name=\"Levski\"")
        .contains(">Stanton<")
        .contains(">Nyx<")
        .contains("data-location-id=\"" + PORT_TRESSLER + "\"")
        .contains("data-location-id=\"" + LORVILLE.id() + "\"")
        .contains("Port Tressler")
        .contains(">1.284<")
        .contains("4 für Staffel Profit")
        .contains("Ohne Ort")
        .contains("data-hl-stays")
        .containsPattern("id=\"hl-submit\"[^>]*disabled")
        .contains("Home Location wählen")
        .contains("Die Wahl ist endgültig.")
        .contains("data-cta=\"Nach {0} umziehen\"")
        .contains("action=\"/logout\"")
        .contains("/js/patchchange/home-location")
        .doesNotContain("data-testid=\"hl-no-locations\"")
        .doesNotContain("??homeLocation");
  }

  @Test
  @WithMockUser
  void showingAPendingPromptDropsTheGatesCachedVerdict() throws Exception {
    when(backendApiClient.get(PROMPT, HomeLocationPromptDto.class))
        .thenReturn(pending(List.of(LORVILLE)));
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(GATE_VERDICT, 1L);

    mockMvc.perform(get(PAGE).session(session)).andExpect(status().isOk());

    assertThat(session.getAttribute(GATE_VERDICT)).isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = MemberRelocationState.class,
      names = {"NOT_ASKED", "RELOCATED", "PHASE_ENDED"})
  @WithMockUser
  void aMemberWithNothingToChooseIsSentIntoTheTool(MemberRelocationState state) throws Exception {
    when(backendApiClient.get(PROMPT, HomeLocationPromptDto.class))
        .thenReturn(
            new HomeLocationPromptDto(state, null, null, null, null, null, List.of(), List.of()));

    mockMvc.perform(get(PAGE)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));
  }

  @Test
  @WithMockUser
  void withoutACuratedLocationThePageSaysAnAdminMustCurateOne() throws Exception {
    when(backendApiClient.get(PROMPT, HomeLocationPromptDto.class)).thenReturn(pending(List.of()));

    assertThat(renderPage())
        .contains("data-testid=\"hl-no-locations\"")
        .contains("Es ist noch keine Home Location gepflegt.")
        .doesNotContain("name=\"locationId\"")
        .containsPattern("id=\"hl-submit\"[^>]*disabled");
  }

  @Test
  @WithMockUser
  void anUnreadablePromptRendersTheErrorInsteadOfRedirecting() throws Exception {
    when(backendApiClient.get(PROMPT, HomeLocationPromptDto.class))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(get(PAGE))
        .andExpect(status().isOk())
        .andExpect(model().attribute("promptError", HomeLocationPageController.PROMPT_ERROR))
        .andExpect(model().attributeDoesNotExist("prompt"));
    assertThat(renderPage())
        .contains("data-testid=\"hl-load-error\"")
        .contains("Die Home-Location-Abfrage konnte nicht geladen werden.")
        .doesNotContain("id=\"hl-form\"")
        .contains("action=\"/logout\"");
  }

  @Test
  @WithMockUser
  void theChoiceIsRelayedAnswersWhatMovedAndDropsTheGatesCachedVerdict() throws Exception {
    when(backendApiClient.post(
            PROMPT, new ChooseHomeLocationRequest(LORVILLE.id()), RelocationResultDto.class))
        .thenReturn(new RelocationResultDto(LORVILLE, 12, 1));
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(GATE_VERDICT, 1L);

    mockMvc
        .perform(
            post(PAGE)
                .session(session)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CHOICE_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.location.name").value("Lorville"))
        .andExpect(jsonPath("$.rows").value(12))
        .andExpect(jsonPath("$.ships").value(1));

    assertThat(session.getAttribute(GATE_VERDICT)).isNull();
  }

  @Test
  @WithMockUser
  void aSecondChoiceComesBackAsTheBackendsConflict() throws Exception {
    when(backendApiClient.post(eq(PROMPT), any(), eq(RelocationResultDto.class)))
        .thenThrow(
            refusal(
                409,
                "BUSINESS_CONFLICT",
                "Die Home Location wurde für diesen Patchwechsel bereits gewählt."));

    mockMvc
        .perform(
            post(PAGE)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CHOICE_JSON))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.detail")
                .value("Die Home Location wurde für diesen Patchwechsel bereits gewählt."));
  }

  @Test
  @WithMockUser
  void aLocationNotOnOfferComesBackAsABadRequest() throws Exception {
    when(backendApiClient.post(eq(PROMPT), any(), eq(RelocationResultDto.class)))
        .thenThrow(refusal(400, "BAD_REQUEST", "Diese Location ist keine wählbare Home Location."));

    mockMvc
        .perform(
            post(PAGE)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CHOICE_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Diese Location ist keine wählbare Home Location."));
  }
}
