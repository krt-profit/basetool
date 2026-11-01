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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.navigation.model.AppearanceResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesAndHiddenUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResponse;
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
 * Renders the hidden pages of REQ-UI-032 through the layout: „Mehr“ only while a page is hidden,
 * the hidden page out of the groups and the quick access, the edit controls and the write relays.
 */
@SpringBootTest
class NavigationHiddenMvcTest {

  private static final String PREFERENCES = "/api/v1/navigation/preferences";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private void stored(long hiddenVersion, String... hidden) {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of(), 0),
                new HiddenResponse(List.of(hidden), hiddenVersion),
                OrderResponse.EMPTY,
                AppearanceResponse.DRAWER));
  }

  private static RequestPostProcessor member() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private static RequestPostProcessor admin() {
    return oidcLogin()
        .authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  @NotNull
  private String page(@NotNull RequestPostProcessor user) throws Exception {
    return mockMvc
        .perform(get("/impressum").with(user))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @NotNull
  private static String quickAccessPages(@NotNull String html) {
    return html.substring(
        html.indexOf("palette-source-pages"), html.indexOf("palette-source-actions"));
  }

  @Test
  @DisplayName("without a hidden page there is no Mehr area, but the state and the edit toggle")
  void rendersNoMoreWithoutAHiddenPage() throws Exception {
    stored(0);

    assertThat(page(member()))
        .doesNotContain("data-testid=\"nav-more\"")
        .contains("data-hidden-version=\"0\"")
        .contains("data-testid=\"nav-edit-toggle\"");
  }

  @Test
  @DisplayName("when the preferences cannot be read there is no edit toggle and no edit control")
  void offersNoEditingWhenThePreferencesAreNotLoaded() throws Exception {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenThrow(new BackendServiceException("down", null, 503));

    assertThat(page(member()))
        .doesNotContain("data-testid=\"nav-edit-toggle\"")
        .doesNotContain("data-nav-edit-controls")
        .doesNotContain("data-testid=\"nav-more\"");
  }

  @Test
  @DisplayName("a hidden page leaves its group and is listed collapsed under Mehr")
  void movesAHiddenPageIntoMore() throws Exception {
    stored(4, "refinery.orders");

    String html = page(member());

    assertThat(html)
        .contains("data-testid=\"nav-more\"")
        .contains("data-testid=\"nav-more-refinery.orders\"")
        .contains("data-hidden-keys=\"refinery.orders\"")
        .contains("data-hidden-version=\"4\"")
        .contains("data-testid=\"nav-unhide-all\"")
        .doesNotContain("data-testid=\"nav-refinery\"");
    assertThat(html).containsPattern("data-collapsed=\"true\"[^>]*data-group-key=\"more\"");
  }

  @Test
  @DisplayName("a page nobody hid keeps its link with a hide control; personal pages get none")
  void offersHideOnPagesOnly() throws Exception {
    stored(0);

    String html = page(member());

    assertThat(row(html, "missions.list")).contains("data-trigger=\"nav-hide\"");
    assertThat(row(html, "personal.profile"))
        .contains("data-favourite-toggle")
        .doesNotContain("nav-hide");
  }

  @NotNull
  private static String row(@NotNull String html, @NotNull String key) {
    int start = html.indexOf("<div class=\"nav-row\" data-entry-key=\"" + key + "\"");
    assertThat(start).as("row of " + key).isNotNegative();
    return html.substring(start, html.indexOf("</div>", html.indexOf("</div>", start) + 1));
  }

  @Test
  @DisplayName("hiding a page takes it and its tabs out of the quick access's page list")
  void hidesTheTabsFromTheQuickAccess() throws Exception {
    stored(0);
    String before = page(member());
    stored(1, "missions.list");

    String after = page(member());

    assertThat(quickAccessPages(before)).contains("href=\"/operations\"");
    assertThat(quickAccessPages(after)).doesNotContain("href=\"/operations\"");
  }

  @Test
  @DisplayName("a group whose links are all hidden stays in the markup for edit mode")
  void keepsAnEmptiedGroupForEditMode() throws Exception {
    stored(1, "refinery.orders", "storage.overview", "orders.create");

    assertThat(page(member()))
        .contains("nav-group-emptied")
        .contains("nav-group-emptied-note")
        .contains("data-testid=\"nav-more-storage.overview\"");
  }

  @Test
  @DisplayName("an administrator's hidden admin page lists under the admin Mehr")
  void listsAnAdminPageInTheAdminMore() throws Exception {
    stored(1, "admin.members");

    assertThat(page(admin()))
        .contains("data-group-key=\"admin-more\"")
        .contains("nav-more-admin.members");
  }

  @Test
  @DisplayName("the swap fragment carries the lists, the state and the quick access's pages")
  void rendersTheSwapFragment() throws Exception {
    stored(9, "refinery.orders");

    String html =
        mockMvc
            .perform(
                get("/navigation/menu").with(member()).header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-hidden-version=\"9\"")
        .contains("data-nav-mode=\"main\"")
        .contains("palette-source-pages")
        .contains("nav-more-refinery.orders")
        .doesNotContain("<html");
  }

  @Test
  @DisplayName("the hidden write relays the stored pages with their new version")
  void relaysTheHiddenWrite() throws Exception {
    when(backendApiClient.put(
            eq(PREFERENCES + "/hidden"),
            eq(new HiddenUpdateRequest(List.of("missions.list"), 1L)),
            eq(HiddenResponse.class)))
        .thenReturn(new HiddenResponse(List.of("missions.list"), 2));

    mockMvc
        .perform(
            put("/navigation/hidden")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[\"missions.list\"],\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
  }

  @Test
  @DisplayName("the combined write relays both sections and refuses an incomplete payload")
  void relaysTheCombinedWrite() throws Exception {
    FavouritesAndHiddenUpdateRequest expected =
        new FavouritesAndHiddenUpdateRequest(
            new FavouritesUpdateRequest(List.of("a.one"), 1L),
            new HiddenUpdateRequest(List.of(), 3L));
    when(backendApiClient.put(
            eq(PREFERENCES + "/favourites-and-hidden"),
            eq(expected),
            eq(NavigationPreferencesResponse.class)))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of("a.one"), 2),
                new HiddenResponse(List.of(), 4),
                OrderResponse.EMPTY,
                AppearanceResponse.DRAWER));

    mockMvc
        .perform(
            put("/navigation/favourites-and-hidden")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"favourites\":{\"keys\":[\"a.one\"],\"version\":1},"
                        + "\"hidden\":{\"keys\":[],\"version\":3}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hidden.version").value(4));

    mockMvc
        .perform(
            put("/navigation/favourites-and-hidden")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"favourites\":{\"keys\":[],\"version\":1}}"))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("VALIDATION"));
  }
}
