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

package de.greluc.krt.profit.basetool.frontend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.navigation.model.AppearanceResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.DesktopMode;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.StartPageUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarsResponse;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The start-page controls of REQ-UI-034 and the full reset of REQ-UI-032: the header button, the
 * selects and the reset dialog of „Darstellung“, the state the script reads, and the relays of the
 * two writes.
 */
@SpringBootTest
class StartPageControlsMvcTest {

  private static final String PREFERENCES = "/api/v1/navigation/preferences";

  private static final String BACKEND_START_PAGE = PREFERENCES + "/appearance/start-page/{mode}";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static NavigationPreferencesResponse preferences(String member, String admin) {
    return new NavigationPreferencesResponse(
        new FavouritesResponse(List.of(), 0),
        HiddenResponse.EMPTY,
        OrderResponse.EMPTY,
        new AppearanceResponse(DesktopMode.DRAWER, true, member, admin, 7),
        TabBarsResponse.AUTOMATIC);
  }

  private void stored(String member, String admin) {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenReturn(preferences(member, admin));
  }

  private static RequestPostProcessor member() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private static RequestPostProcessor admin() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  @NotNull
  private String page(@NotNull String path, @NotNull RequestPostProcessor user) throws Exception {
    return mockMvc
        .perform(get(path).with(user))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @DisplayName(
      "a page header offers 'Als Startseite festlegen', unpressed until the page is chosen")
  void theHeaderButtonOffersTheStartPage() throws Exception {
    stored(null, null);

    String html = page("/profile", member());

    assertThat(html)
        .containsPattern(
            "data-testid=\"nav-start-page\"[^>]*data-entry-key=\"personal.profile\"[^>]*data-mode=\"member\"")
        .containsPattern("data-testid=\"nav-start-page\"[^>]*aria-pressed=\"false\"");
  }

  @Test
  @DisplayName("the header button of the chosen start page is pressed")
  void theChosenStartPageIsPressed() throws Exception {
    stored("personal.profile", null);

    assertThat(page("/profile", member()))
        .containsPattern("data-testid=\"nav-start-page\"[^>]*aria-pressed=\"true\"");
  }

  @Test
  @DisplayName("a detail page that is no entry carries no start-page button")
  void aNonEntryPageHasNoButton() throws Exception {
    stored(null, null);

    assertThat(page("/impressum", member())).doesNotContain("data-testid=\"nav-start-page\"");
  }

  @Test
  @DisplayName("the state element carries both start pages for the script")
  void theStateCarriesTheStartPages() throws Exception {
    stored("missions.list", "admin.members");

    assertThat(page("/impressum", admin()))
        .containsPattern("data-appearance-state[^>]*data-start-member=\"missions.list\"")
        .containsPattern("data-appearance-state[^>]*data-start-admin=\"admin.members\"");
  }

  @Test
  @DisplayName("the profile offers a select per mode, with the stored page selected")
  void theProfileOffersASelectPerMode() throws Exception {
    stored("missions.list", "admin.members");

    String html = page("/profile", admin());

    assertThat(html)
        .contains("data-testid=\"start-page-select-member\"")
        .contains("data-testid=\"start-page-select-admin\"")
        .containsPattern("<option value=\"missions.list\"\\s+selected")
        .containsPattern("<option value=\"admin.members\"\\s+selected");
  }

  @Test
  @DisplayName("a member sees no select for the administration mode")
  void aMemberHasNoAdminSelect() throws Exception {
    stored(null, null);

    String html = page("/profile", member());

    assertThat(html)
        .contains("data-testid=\"start-page-select-member\"")
        .doesNotContain("data-testid=\"start-page-select-admin\"");
    assertThat(html).doesNotContain("<option value=\"admin.members\"");
  }

  @Test
  @DisplayName("the profile carries the full reset behind a confirmation dialog")
  void theProfileCarriesTheFullReset() throws Exception {
    stored(null, null);

    String html = page("/profile", member());

    assertThat(html)
        .contains("data-testid=\"navigation-reset-open\"")
        .containsPattern("<dialog[^>]*id=\"navigation-reset-modal\"")
        .contains("data-testid=\"navigation-reset-confirm\"");
  }

  @Test
  @DisplayName("an unreadable store disables the controls")
  void anUnreadableStoreDisablesTheControls() throws Exception {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenThrow(new BackendServiceException("down", null, 503));

    assertThat(page("/profile", member()))
        .containsPattern("data-testid=\"nav-start-page\"[^>]*disabled");
  }

  @Test
  @DisplayName("the start-page write is relayed with the version and answers the stored section")
  void relaysTheStartPageWrite() throws Exception {
    when(backendApiClient.put(
            eq(BACKEND_START_PAGE),
            any(StartPageUpdateRequest.class),
            eq(AppearanceResponse.class),
            eq("member")))
        .thenReturn(new AppearanceResponse(DesktopMode.DRAWER, true, "missions.list", null, 8));

    mockMvc
        .perform(
            put("/navigation/appearance/start-page/member")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"missions.list\",\"version\":7}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.startPageMember").value("missions.list"))
        .andExpect(jsonPath("$.version").value(8));

    verify(backendApiClient)
        .put(
            BACKEND_START_PAGE,
            new StartPageUpdateRequest("missions.list", 7L),
            AppearanceResponse.class,
            "member");
  }

  @Test
  @DisplayName("a start-page write without a version is refused before the backend")
  void refusesAWriteWithoutAVersion() throws Exception {
    mockMvc
        .perform(
            put("/navigation/appearance/start-page/member")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"missions.list\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("VALIDATION"));
  }

  @Test
  @DisplayName("an unknown mode is not found")
  void anUnknownModeIsNotFound() throws Exception {
    mockMvc
        .perform(
            put("/navigation/appearance/start-page/tablet")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":null,\"version\":7}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("the reset is relayed and answers the cleared sections")
  void relaysTheReset() throws Exception {
    when(backendApiClient.post(PREFERENCES + "/reset", null, NavigationPreferencesResponse.class))
        .thenReturn(preferences(null, null));

    mockMvc
        .perform(
            post("/navigation/reset")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.appearance.desktopMode").value("DRAWER"))
        .andExpect(jsonPath("$.appearance.startPageMember").doesNotExist());
  }
}
