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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationOptionDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationPreviewDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationPromptDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.MemberRelocationState;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC test of {@link AdminMemberHomeLocationPageController} (REQ-PATCH-022): an admin sees the
 * member's state in the latest home-location phase under the member's name and tabs, and while the
 * member is pending what would move and the choice on the member's behalf; the content fragment
 * swaps in place, a failed read renders an error, and a member is refused.
 */
@SpringBootTest
class AdminMemberHomeLocationPageControllerMvcTest {

  private static final UUID MEMBER = UUID.fromString("3e3be3be-0000-4000-8000-000000000007");
  private static final String PAGE = "/admin/members/" + MEMBER + "/home-location";
  private static final String PROMPT = "/api/v1/patch-changes/admin/members/{userId}/home-location";
  private static final String USER = "/api/v1/users/{id}";
  private static final UUID PATCH_CHANGE = UUID.fromString("7a0c7a0c-0000-4000-8000-0000000000a4");
  private static final Instant LIVE_AT = Instant.parse("2026-11-14T20:00:00Z");

  private static final HomeLocationOptionDto LORVILLE =
      new HomeLocationOptionDto(
          UUID.fromString("10c4710c-0000-4000-8000-000000000001"), "Lorville", "Stanton");
  private static final HomeLocationOptionDto ORISON =
      new HomeLocationOptionDto(
          UUID.fromString("10c4710c-0000-4000-8000-000000000003"), "Orison", null);

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** An admin signed in through the identity provider, as the navigation needs one. */
  private static RequestPostProcessor admin() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  /**
   * Stubs the member's prompt and the member record whose name the page head shows.
   *
   * @param prompt the member's prompt
   */
  private void stub(HomeLocationPromptDto prompt) {
    when(backendApiClient.get(PROMPT, HomeLocationPromptDto.class, MEMBER)).thenReturn(prompt);
    when(backendApiClient.get(USER, UserDto.class, MEMBER))
        .thenReturn(
            new UserDto(
                MEMBER,
                "pilot",
                "Pilot Alpha",
                "Pilot Alpha",
                null,
                1,
                null,
                Set.of(),
                Set.of(),
                null,
                false,
                false,
                true,
                null,
                null,
                0L,
                null,
                null));
  }

  /**
   * A prompt of patch 4.4 in the given state, without a choice.
   *
   * @param state where the member stands
   * @param preview what would move
   * @return the prompt
   */
  private static HomeLocationPromptDto prompt(
      MemberRelocationState state, List<HomeLocationPreviewDto> preview) {
    return new HomeLocationPromptDto(
        state, PATCH_CHANGE, "4.4", LIVE_AT, null, null, List.of(LORVILLE, ORISON), preview);
  }

  /**
   * Renders the page for an admin and returns its markup.
   *
   * @return the rendered page
   * @throws Exception if the request fails
   */
  private String renderPage() throws Exception {
    return mockMvc
        .perform(get(PAGE).with(admin()))
        .andExpect(status().isOk())
        .andExpect(view().name("patchchange/admin/member-home-location"))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void aPendingMemberShowsWhatMovesAndTheChoiceOnTheirBehalf() throws Exception {
    stub(
        prompt(
            MemberRelocationState.PENDING,
            List.of(new HomeLocationPreviewDto(LORVILLE.id(), "Lorville", 7L, List.of(), 2L))));

    assertThat(renderPage())
        .contains("Home Location von Pilot Alpha")
        .contains("data-testid=\"member-tabs\"")
        .containsPattern("class=\"tab active\"[^>]*data-testid=\"member-tab-link-home-location\"")
        .contains("data-state=\"PENDING\"")
        .contains("class=\"chip chip--warning\"")
        .contains("Ausstehend")
        .contains("Patch 4.4: Das Mitglied hat noch keine neue Home Location gewählt.")
        .contains("data-testid=\"hl-preview\"")
        .contains("data-location-id=\"" + LORVILLE.id() + "\"")
        .contains("id=\"mhl-form\"")
        .contains("data-label=\"4.4\"")
        .containsPattern("value=\"" + LORVILLE.id() + "\"[^>]*data-location-name=\"Lorville\"")
        .contains(">Lorville (Stanton)<")
        .contains(">Orison<")
        .containsPattern("id=\"mhl-open\"[^>]*disabled")
        .contains("Für das Mitglied umziehen")
        .contains("id=\"mhl-confirm-modal\"")
        .contains("data-confirm-text=\"Alles, was das Mitglied vor der Live-Zeit von Patch {0}")
        .contains("data-page-url=\"/admin/members/" + MEMBER + "/home-location\"")
        .contains("/js/patchchange/admin-member-home-location")
        .doesNotContain("??admin.memberHomeLocation")
        .doesNotContain("??homeLocation");
  }

  @Test
  void aRelocatedMemberShowsWhereAndWhenWithoutAChoice() throws Exception {
    stub(
        new HomeLocationPromptDto(
            MemberRelocationState.RELOCATED,
            PATCH_CHANGE,
            "4.4",
            LIVE_AT,
            LORVILLE,
            Instant.parse("2026-11-15T09:30:00Z"),
            List.of(LORVILLE),
            List.of()));

    assertThat(renderPage())
        .contains("data-state=\"RELOCATED\"")
        .contains("class=\"chip chip--success\"")
        .contains("Patch 4.4: Umgezogen nach Lorville.")
        .contains("datetime=\"2026-11-15T09:30:00Z\"")
        .contains("15.11.2026 09:30 UTC")
        .doesNotContain("id=\"mhl-form\"")
        .doesNotContain("data-testid=\"hl-preview\"")
        .contains("id=\"mhl-confirm-modal\"");
  }

  @Test
  void aMemberWhosePhaseEndedOrWhoIsNotAskedHasNoChoice() throws Exception {
    stub(prompt(MemberRelocationState.PHASE_ENDED, List.of()));

    assertThat(renderPage())
        .contains("data-state=\"PHASE_ENDED\"")
        .contains("Patch 4.4: Die Abfrage wurde beendet, bevor das Mitglied gewählt hat.")
        .doesNotContain("id=\"mhl-form\"");

    stub(
        new HomeLocationPromptDto(
            MemberRelocationState.NOT_ASKED, null, null, null, null, null, List.of(), List.of()));

    assertThat(renderPage())
        .contains("data-state=\"NOT_ASKED\"")
        .contains("Das Mitglied wird derzeit nach keiner Home Location gefragt.")
        .doesNotContain("id=\"mhl-form\"");
  }

  @Test
  void aPendingMemberWithoutACuratedLocationCannotBeRelocated() throws Exception {
    stub(
        new HomeLocationPromptDto(
            MemberRelocationState.PENDING,
            PATCH_CHANGE,
            "4.4",
            LIVE_AT,
            null,
            null,
            List.of(),
            List.of()));

    assertThat(renderPage())
        .contains("data-testid=\"mhl-no-locations\"")
        .contains("id=\"hl-preview-empty\"")
        .doesNotContain("id=\"mhl-form\"");
  }

  @Test
  void theContentFragmentRendersOnlyTheContentWithoutReadingTheMember() throws Exception {
    stub(prompt(MemberRelocationState.PENDING, List.of()));

    String html =
        mockMvc
            .perform(get(PAGE).param("fragment", "content").with(admin()))
            .andExpect(status().isOk())
            .andExpect(view().name("patchchange/admin/member-home-location :: content"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-testid=\"mhl-state\"")
        .contains("id=\"mhl-form\"")
        .doesNotContain("id=\"mhl-content\"")
        .doesNotContain("id=\"mhl-confirm-modal\"");
    verify(backendApiClient, never()).get(eq(USER), eq(UserDto.class), eq(MEMBER));
  }

  @Test
  void anUnreadablePromptRendersAnErrorInPlaceOfTheContent() throws Exception {
    when(backendApiClient.get(PROMPT, HomeLocationPromptDto.class, MEMBER))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(get(PAGE).with(admin()))
        .andExpect(status().isOk())
        .andExpect(
            model().attribute("promptError", AdminMemberHomeLocationPageController.PROMPT_ERROR))
        .andExpect(model().attributeDoesNotExist("prompt"));
    assertThat(renderPage())
        .contains("data-testid=\"mhl-load-error\"")
        .contains("Die Home Location des Mitglieds konnte nicht geladen werden.")
        .contains("Home Location des Mitglieds")
        .doesNotContain("data-testid=\"mhl-state\"");
  }

  @Test
  void theMemberTabsOfferTheHomeLocationTab() throws Exception {
    stub(prompt(MemberRelocationState.NOT_ASKED, List.of()));

    assertThat(renderPage())
        .containsPattern(
            "href=\"/admin/members/"
                + MEMBER
                + "/home-location\"[^>]*data-testid=\"member-tab-link-home-location\"")
        .contains("data-testid=\"member-tab-link-hangar\"");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMemberIsRefused() throws Exception {
    mockMvc.perform(get(PAGE)).andExpect(status().isForbidden());

    verify(backendApiClient, never()).get(eq(PROMPT), eq(HomeLocationPromptDto.class), eq(MEMBER));
  }
}
