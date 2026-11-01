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
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarsResponse;
import java.util.Arrays;
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
 * Renders the configurable phone tab bar of REQ-UI-032 through the layout: the automatic fill from
 * the favourites, the stored bar, the stand-in, one bar per mode, the „Anpassen“ pickers and the
 * write relay.
 */
@SpringBootTest
class NavigationTabBarMvcTest {

  private static final String PREFERENCES = "/api/v1/navigation/preferences";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private void stored(List<String> favourites, TabBarResponse member, TabBarResponse admin) {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(favourites, 2),
                HiddenResponse.EMPTY,
                new TabBarsResponse(member, admin)));
  }

  private static TabBarResponse bar(long version, String... slots) {
    return new TabBarResponse(Arrays.asList(slots), version);
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
  private String page(@NotNull RequestPostProcessor user, @NotNull String path) throws Exception {
    return mockMvc
        .perform(get(path).with(user))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @NotNull
  private static String bar(@NotNull String html) {
    return html.substring(
        html.indexOf("<nav class=\"mobile-tabbar\""),
        html.indexOf("</nav>", html.indexOf("mobile-tabbar")));
  }

  @NotNull
  private static String memberSlots(@NotNull String tabBar) {
    int start = tabBar.indexOf("data-tabbar-mode=\"member\"");
    int end = tabBar.indexOf("data-tabbar-mode=\"admin\"");
    return end < 0 ? tabBar.substring(start) : tabBar.substring(start, end);
  }

  @Test
  @DisplayName("an unedited bar shows the three defaults, then Meldungen and Menü")
  void defaultBar() throws Exception {
    stored(List.of(), TabBarResponse.AUTOMATIC, TabBarResponse.AUTOMATIC);

    String tabBar = bar(page(member(), "/impressum"));

    assertThat(tabBar)
        .containsSubsequence(
            "data-testid=\"mobile-tab-missions.list\"",
            "data-testid=\"mobile-tab-storage.overview\"",
            "data-testid=\"mobile-tab-orders.create\"",
            "data-testid=\"mobile-tab-notifications\"",
            "data-testid=\"mobile-tab-menu\"");
    assertThat(tabBar).doesNotContain("data-tabbar-mode=\"admin\"");
  }

  @Test
  @DisplayName("an automatic bar fills from the favourites first")
  void automaticBarTakesTheFavourites() throws Exception {
    stored(List.of("refinery.orders"), TabBarResponse.AUTOMATIC, TabBarResponse.AUTOMATIC);

    assertThat(memberSlots(bar(page(member(), "/impressum"))))
        .containsSubsequence(
            "data-testid=\"mobile-tab-refinery.orders\"",
            "data-testid=\"mobile-tab-missions.list\"",
            "data-testid=\"mobile-tab-storage.overview\"")
        .doesNotContain("mobile-tab-orders.create");
  }

  @Test
  @DisplayName("a stored bar keeps its choice and ignores the favourites")
  void storedBarIgnoresTheFavourites() throws Exception {
    stored(
        List.of("refinery.orders"),
        bar(5, "storage.overview", "orders.create", "missions.list"),
        TabBarResponse.AUTOMATIC);

    assertThat(memberSlots(bar(page(member(), "/impressum"))))
        .containsSubsequence(
            "data-testid=\"mobile-tab-storage.overview\"",
            "data-testid=\"mobile-tab-orders.create\"",
            "data-testid=\"mobile-tab-missions.list\"")
        .doesNotContain("mobile-tab-refinery.orders")
        .doesNotContain("is-standin");
  }

  @Test
  @DisplayName("a stored slot this context cannot open shows a marked stand-in")
  void standInIsMarked() throws Exception {
    stored(
        List.of("refinery.orders"),
        bar(5, "orders.list", "missions.list", "storage.overview"),
        TabBarResponse.AUTOMATIC);

    String slots = memberSlots(bar(page(member(), "/impressum")));

    assertThat(slots)
        .containsPattern("is-standin[^>]*data-testid=\"mobile-tab-refinery.orders\"")
        .doesNotContain("mobile-tab-orders.list");
  }

  @Test
  @DisplayName("an administrator gets a second set of slots with the admin defaults")
  void adminBarHasItsOwnSlots() throws Exception {
    stored(List.of(), TabBarResponse.AUTOMATIC, TabBarResponse.AUTOMATIC);

    String tabBar = bar(page(admin(), "/impressum"));

    assertThat(tabBar)
        .containsSubsequence(
            "data-tabbar-mode=\"admin\"",
            "data-testid=\"mobile-tab-admin.dashboard\"",
            "data-testid=\"mobile-tab-admin.members\"",
            "data-testid=\"mobile-tab-admin.auditlog\"");
    assertThat(tabBar).containsPattern("data-tabbar-mode=\"member\"(?![^>]*hidden)");
    assertThat(page(admin(), "/admin")).containsPattern("data-tabbar-mode=\"member\"[^>]*hidden");
  }

  @Test
  @DisplayName("Anpassen carries one picker per slot and the state of both bars")
  void editorAndState() throws Exception {
    stored(
        List.of("refinery.orders"),
        bar(7, "storage.overview", null, null),
        bar(3, null, null, null));

    String html = page(admin(), "/impressum");

    assertThat(html)
        .contains("data-tabbar-member-version=\"7\"")
        .contains("data-tabbar-member-stored=\"storage.overview - -\"")
        .contains("data-tabbar-admin-version=\"3\"")
        .contains("data-tabbar-admin-stored=\"- - -\"")
        .contains("data-testid=\"nav-tabbar-slot-0\"")
        .contains("data-testid=\"nav-tabbar-slot-2\"")
        .contains("data-testid=\"nav-tabbar-reset\"")
        .contains("data-testid=\"tabbar-picker\"")
        .containsPattern("data-tabbar-member-shown=\"storage.overview [a-z.]+ [a-z.]+\"");
  }

  @Test
  @DisplayName("an automatic bar has nothing to reset")
  void resetIsDisabledWhileAutomatic() throws Exception {
    stored(List.of(), TabBarResponse.AUTOMATIC, TabBarResponse.AUTOMATIC);

    assertThat(page(member(), "/impressum"))
        .containsPattern(
            "data-testid=\"nav-tabbar-reset\"[^>]*disabled|disabled[^>]*data-testid=\"nav-tabbar-reset\"");
  }

  @Test
  @DisplayName("when the preferences cannot be read the defaults render and nothing is editable")
  void failsClosedToTheDefaults() throws Exception {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenThrow(new BackendServiceException("down", null, 503));

    String html = page(member(), "/impressum");

    assertThat(bar(html))
        .containsSubsequence(
            "mobile-tab-missions.list", "mobile-tab-storage.overview", "mobile-tab-orders.create");
    assertThat(html)
        .doesNotContain("data-testid=\"nav-tabbar-edit\"")
        .doesNotContain("tabbar-picker-item");
  }

  @Test
  @DisplayName("the swap fragment carries the slot sets and the pickers")
  void swapFragmentCarriesTheBar() throws Exception {
    stored(List.of(), TabBarResponse.AUTOMATIC, TabBarResponse.AUTOMATIC);

    String html =
        mockMvc
            .perform(
                get("/navigation/menu").with(admin()).header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-nav-swap=\"tabbar-slots-member\"")
        .contains("data-nav-swap=\"tabbar-slots-admin\"")
        .contains("data-nav-swap=\"tabbar-picker-member\"")
        .contains("mobile-tab-missions.list");
  }

  @Test
  @DisplayName("the relay forwards one mode's slots with the version and refuses a bad request")
  void relaysTheWrite() throws Exception {
    TabBarUpdateRequest expected =
        new TabBarUpdateRequest(Arrays.asList("a.one", null, "c.three"), 4L);
    when(backendApiClient.put(
            eq(PREFERENCES + "/tab-bar/member"), eq(expected), eq(TabBarResponse.class)))
        .thenReturn(bar(5, "a.one", null, "c.three"));

    mockMvc
        .perform(
            put("/navigation/tab-bar/member")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slots\":[\"a.one\",null,\"c.three\"],\"version\":4}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(5));

    mockMvc
        .perform(
            put("/navigation/tab-bar/member")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slots\":[\"a.one\",null,null]}"))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("VALIDATION"));
    mockMvc
        .perform(
            put("/navigation/tab-bar/tablet")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slots\":[null,null,null],\"version\":0}"))
        .andExpect(status().isNotFound());
  }
}
