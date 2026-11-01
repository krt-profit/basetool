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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
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
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResponse;
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
 * Renders the favourites of REQ-UI-032 through the layout: the Favoriten group, a favourite the
 * active context does not offer, the fragment the star swaps and the write relay.
 */
@SpringBootTest
class NavigationFavouritesMvcTest {

  private static final String PREFERENCES = "/api/v1/navigation/preferences";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private void stored(long version, String... keys) {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of(keys), version),
                HiddenResponse.EMPTY,
                OrderResponse.EMPTY,
                AppearanceResponse.DRAWER,
                TabBarsResponse.AUTOMATIC));
  }

  private static RequestPostProcessor member() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
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

  @Test
  @DisplayName("a favourite renders in a Favoriten group above the task groups, as a copy")
  void rendersTheFavouritesGroupAtTheTop() throws Exception {
    stored(2, "refinery.orders");

    String html = page(member());

    assertThat(html)
        .contains("data-testid=\"nav-favourites\"")
        .contains("data-testid=\"nav-favourite-refinery.orders\"")
        .contains("data-favourites-state")
        .contains("data-version=\"2\"")
        .contains("data-keys=\"refinery.orders\"")
        .contains("data-max=\"12\"")
        .contains("data-testid=\"nav-refinery\"");
    assertThat(html.indexOf("data-testid=\"nav-favourites\""))
        .isLessThan(html.indexOf("data-group-key=\"operations\""));
  }

  @Test
  @DisplayName("without a favourite no group renders, but the state element does")
  void rendersNoGroupWithoutFavourites() throws Exception {
    stored(0);

    assertThat(page(member()))
        .doesNotContain("data-testid=\"nav-favourites\"")
        .contains("data-favourites-state")
        .contains("data-loaded=\"true\"");
  }

  @Test
  @DisplayName("a favourite the active context does not offer renders greyed with the hint")
  void rendersAnUnavailableFavouriteGreyed() throws Exception {
    stored(1, "orders.list");

    String html = page(member());

    assertThat(html)
        .contains("class=\"nav-link is-unavailable\"")
        .contains("data-testid=\"nav-favourite-orders.list\"")
        .contains("aria-disabled=\"true\"")
        .containsPattern("nav-unavailable-hint\"[^>]*>(Im aktuellen Kontext|Not available)");
  }

  @Test
  @DisplayName("an entry whose role the caller lost is deleted on the load and does not render")
  void deletesAFavouriteWhoseRoleIsGone() throws Exception {
    stored(3, "admin.terms", "refinery.orders");
    when(backendApiClient.post(
            eq(PREFERENCES + "/prune"), any(), eq(NavigationPreferencesResponse.class)))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of("refinery.orders"), 4),
                HiddenResponse.EMPTY,
                OrderResponse.EMPTY,
                AppearanceResponse.DRAWER,
                TabBarsResponse.AUTOMATIC));

    String html = page(member());

    assertThat(html)
        .doesNotContain("nav-favourite-admin.terms")
        .contains("nav-favourite-refinery.orders")
        .contains("data-version=\"4\"");
  }

  @Test
  @DisplayName("when the preferences cannot be read the favourites stay off and the page renders")
  void failsClosedWhenTheBackendFails() throws Exception {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenThrow(new BackendServiceException("down", null, 503));

    assertThat(page(member()))
        .doesNotContain("data-testid=\"nav-favourites\"")
        .contains("data-loaded=\"false\"");
  }

  @Test
  @DisplayName("a signed-out visitor reads no preferences")
  void anonymousReadsNothing() throws Exception {
    String html =
        mockMvc
            .perform(get("/impressum").with(anonymous()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).doesNotContain("data-favourites-state");
  }

  @Test
  @DisplayName("the page head of an entry carries the star, pressed for a favourite")
  void theHeaderStarFollowsTheFavourites() throws Exception {
    stored(2, "missions.list");

    String html =
        mockMvc
            .perform(get("/missions").with(member()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .containsPattern("<button[^>]*data-favourite-toggle[^>]*data-entry-key=\"missions.list\"")
        .containsPattern("data-entry-key=\"missions.list\"[^>]*aria-pressed=\"true\"");
  }

  @Test
  @DisplayName("the star is disabled at the cap for an entry that is no favourite yet")
  void theStarIsDisabledAtTheCap() throws Exception {
    stored(
        1,
        "hangar.overview",
        "storage.overview",
        "refinery.orders",
        "operations.list",
        "hangar.catalog",
        "hangar.unit",
        "storage.levels",
        "storage.my",
        "storage.all",
        "personal.inventory",
        "personal.blueprints",
        "orders.create");

    String html =
        mockMvc
            .perform(get("/missions").with(member()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).containsPattern("<button[^>]*data-entry-key=\"missions.list\"[^>]*disabled");
  }

  @Test
  @DisplayName("the swap fragment renders the state and both modes' favourites")
  void rendersTheSwapFragment() throws Exception {
    stored(7, "refinery.orders");

    String html =
        mockMvc
            .perform(
                get("/navigation/menu").with(member()).header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-version=\"7\"")
        .contains("data-favourites-mode=\"member\"")
        .contains("nav-favourite-refinery.orders")
        .doesNotContain("<html");
  }

  @Test
  @DisplayName("the swap fragment is only for background reads")
  void swapFragmentNeedsTheBackgroundMarker() throws Exception {
    stored(0);

    mockMvc.perform(get("/navigation/menu").with(member())).andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("the write relays the stored favourites with their new version")
  void relaysTheWrite() throws Exception {
    when(backendApiClient.put(
            eq(PREFERENCES + "/favourites"),
            eq(new FavouritesUpdateRequest(List.of("missions.list"), 1L)),
            eq(FavouritesResponse.class)))
        .thenReturn(new FavouritesResponse(List.of("missions.list"), 2));

    mockMvc
        .perform(
            put("/navigation/favourites")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[\"missions.list\"],\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.keys[0]").value("missions.list"));
  }

  @Test
  @DisplayName("an incomplete write is refused with the validation code")
  void refusesAnIncompleteWrite() throws Exception {
    mockMvc
        .perform(
            put("/navigation/favourites")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[\"missions.list\"]}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION"));
  }

  @Test
  @DisplayName("a stale version is relayed as a 409 for the page to reload the favourites alone")
  void relaysAConflict() throws Exception {
    when(backendApiClient.put(eq(PREFERENCES + "/favourites"), any(), eq(FavouritesResponse.class)))
        .thenThrow(new BackendServiceException("stale", null, 409));

    mockMvc
        .perform(
            put("/navigation/favourites")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[],\"version\":0}"))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("a signed-out caller cannot write")
  void anonymousCannotWrite() throws Exception {
    mockMvc
        .perform(
            put("/navigation/favourites")
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[],\"version\":0}"))
        .andExpect(status().isUnauthorized());
  }
}
