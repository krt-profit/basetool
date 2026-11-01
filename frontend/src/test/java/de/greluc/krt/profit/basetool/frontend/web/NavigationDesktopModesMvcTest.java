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
import de.greluc.krt.profit.basetool.frontend.navigation.model.AppearanceUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.DesktopMode;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResponse;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Renders the three desktop modes of REQ-UI-026 through the layout: the mode on the page, the
 * switch in the sidebar head, the rail with its flyouts and its foot, failing closed, and the write
 * relay of the appearance section.
 */
@SpringBootTest
class NavigationDesktopModesMvcTest {

  private static final String PREFERENCES = "/api/v1/navigation/preferences";

  private static final String APPEARANCE = PREFERENCES + "/appearance";

  private static final Pattern RAIL =
      Pattern.compile("<nav id=\"nav-rail\".*?</nav>", Pattern.DOTALL);

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private void stored(DesktopMode mode, long version, String... favourites) {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of(favourites), 0),
                HiddenResponse.EMPTY,
                OrderResponse.EMPTY,
                new AppearanceResponse(mode, version)));
  }

  private static RequestPostProcessor member() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private static RequestPostProcessor admin() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
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
  private static String rail(@NotNull String html) {
    Matcher matcher = RAIL.matcher(html);
    assertThat(matcher.find()).as("the rail renders").isTrue();
    return matcher.group();
  }

  @Test
  @DisplayName("the stored mode is on the body, and the default is the drawer")
  void theBodyCarriesTheStoredMode() throws Exception {
    stored(DesktopMode.RAIL, 3);
    assertThat(page(member())).containsPattern("<body[^>]*data-nav-mode=\"rail\"");

    stored(DesktopMode.PINNED, 3);
    assertThat(page(member())).containsPattern("<body[^>]*data-nav-mode=\"pinned\"");

    stored(DesktopMode.DRAWER, 0);
    assertThat(page(member())).containsPattern("<body[^>]*data-nav-mode=\"drawer\"");
  }

  @Test
  @DisplayName("the sidebar head holds a three-state switch with the stored mode selected")
  void theSidebarHeadHoldsTheSwitch() throws Exception {
    stored(DesktopMode.RAIL, 5);

    String html = page(member());

    assertThat(html)
        .contains("data-appearance-state")
        .contains("data-version=\"5\"")
        .contains("data-testid=\"segment-desktop-mode-sidebar-drawer\"")
        .contains("data-testid=\"segment-desktop-mode-sidebar-rail\"")
        .contains("data-testid=\"segment-desktop-mode-sidebar-pinned\"")
        .containsPattern("value=\"rail\"[^>]*checked")
        .doesNotContainPattern("value=\"drawer\"[^>]*checked");
  }

  @Test
  @DisplayName("without readable preferences the drawer renders and the state says not loaded")
  void failsClosedToTheDrawer() throws Exception {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenThrow(new BackendServiceException("down", null, 503));

    String html = page(member());

    assertThat(html)
        .containsPattern("<body[^>]*data-nav-mode=\"drawer\"")
        .contains("data-appearance-state")
        .contains("data-loaded=\"false\"");
  }

  @Test
  @DisplayName("the rail has one disclosure button per task group, labelled by the group name")
  void theRailHasOneButtonPerGroup() throws Exception {
    stored(DesktopMode.RAIL, 1);

    String rail = rail(page(member()));

    assertThat(rail)
        .contains("data-testid=\"nav-rail-group-operations\"")
        .contains("data-testid=\"nav-rail-group-logistics\"")
        .contains("data-testid=\"nav-rail-group-trade\"")
        .contains("data-testid=\"nav-rail-group-unit\"")
        .doesNotContain("data-testid=\"nav-rail-group-bank\"")
        .doesNotContain("nav-rail-group-favourites");
    Matcher button =
        Pattern.compile(
                "<button[^>]*data-testid=\"nav-rail-group-operations\"[^>]*>", Pattern.DOTALL)
            .matcher(rail);
    assertThat(button.find()).isTrue();
    assertThat(button.group())
        .contains("aria-expanded=\"false\"")
        .containsPattern("aria-controls=\"nav-rail-flyout-operations\"")
        .containsPattern("aria-label=\"(Einsatz|Operations)")
        .doesNotContain("aria-haspopup")
        .doesNotContain("role=\"menu");
  }

  @Test
  @DisplayName("a flyout is headed by the group name and lists the group's links, with their keys")
  void aFlyoutListsItsGroup() throws Exception {
    stored(DesktopMode.RAIL, 1);

    String rail = rail(page(member()));

    Matcher flyout =
        Pattern.compile(
                "<div[^>]*id=\"nav-rail-flyout-operations\".*?</div>\\s*</div>", Pattern.DOTALL)
            .matcher(rail);
    assertThat(flyout.find()).isTrue();
    assertThat(flyout.group())
        .contains("hidden")
        .contains("nav-rail-flyout-head")
        .contains("data-entry-key=\"missions.list\"")
        .contains("data-entry-key=\"hangar.overview\"");
  }

  @Test
  @DisplayName("the Favoriten button renders only with at least one favourite, and first")
  void theFavouritesButtonNeedsAFavourite() throws Exception {
    stored(DesktopMode.RAIL, 1);
    assertThat(rail(page(member()))).doesNotContain("nav-rail-group-favourites");

    stored(DesktopMode.RAIL, 1, "refinery.orders");
    String rail = rail(page(member()));

    assertThat(rail).contains("data-testid=\"nav-rail-group-favourites\"");
    assertThat(rail.indexOf("nav-rail-group-favourites"))
        .isLessThan(rail.indexOf("nav-rail-group-operations"));
    assertThat(rail).contains("data-entry-key=\"refinery.orders\"");
  }

  @Test
  @DisplayName("the foot holds the avatar button for everyone and the admin toggle only for admins")
  void theFootHoldsAvatarAndAdminToggle() throws Exception {
    stored(DesktopMode.RAIL, 1);

    String memberRail = rail(page(member()));
    String adminRail = rail(page(admin()));

    assertThat(memberRail)
        .contains("data-testid=\"nav-rail-avatar\"")
        .doesNotContain("data-testid=\"nav-rail-admin\"");
    assertThat(adminRail)
        .contains("data-testid=\"nav-rail-avatar\"")
        .contains("data-testid=\"nav-rail-admin\"");
  }

  @Test
  @DisplayName("the avatar flyout holds the personal pages, the user row and the switch")
  void theAvatarFlyoutHoldsThePersonalPanel() throws Exception {
    stored(DesktopMode.RAIL, 1);

    Matcher flyout =
        Pattern.compile("<div[^>]*id=\"nav-rail-flyout-avatar\".*</nav>", Pattern.DOTALL)
            .matcher(rail(page(member())));

    assertThat(flyout.find()).isTrue();
    assertThat(flyout.group())
        .contains("data-entry-key=\"personal.profile\"")
        .contains("nav-rail-user")
        .contains("data-testid=\"segment-desktop-mode-rail-rail\"");
  }

  @Test
  @DisplayName("the Mehr button renders only while a page is hidden, and lists the hidden page")
  void theMoreButtonNeedsAHiddenPage() throws Exception {
    stored(DesktopMode.RAIL, 1);
    assertThat(rail(page(member()))).doesNotContain("nav-rail-group-more");

    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of(), 0),
                new HiddenResponse(List.of("refinery.orders"), 2),
                OrderResponse.EMPTY,
                new AppearanceResponse(DesktopMode.RAIL, 1)));
    String rail = rail(page(member()));

    assertThat(rail)
        .contains("data-testid=\"nav-rail-group-more\"")
        .containsPattern("id=\"nav-rail-flyout-more\"[\\s\\S]*data-entry-key=\"refinery.orders\"");
  }

  @Test
  @DisplayName("the avatar flyout offers Anpassen, which opens the sidebar as an overlay")
  void theAvatarFlyoutOffersAnpassen() throws Exception {
    stored(DesktopMode.RAIL, 1);

    assertThat(rail(page(member()))).contains("data-testid=\"nav-rail-edit\"");
  }

  @Test
  @DisplayName("an administrator's rail carries the three admin groups too")
  void theAdminRailCarriesTheAdminGroups() throws Exception {
    stored(DesktopMode.RAIL, 1);

    String rail = rail(page(admin()));

    assertThat(rail)
        .contains("data-nav-rail-mode=\"admin\"")
        .contains("data-testid=\"nav-rail-group-admin-members\"");
  }

  @Test
  @DisplayName("the appearance write is relayed with the version and answers the stored section")
  void relaysTheAppearanceWrite() throws Exception {
    when(backendApiClient.put(
            eq(APPEARANCE), any(AppearanceUpdateRequest.class), eq(AppearanceResponse.class)))
        .thenReturn(new AppearanceResponse(DesktopMode.PINNED, 4));

    mockMvc
        .perform(
            put("/navigation/appearance")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"desktopMode\":\"PINNED\",\"version\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.desktopMode").value("PINNED"))
        .andExpect(jsonPath("$.version").value(4));
  }

  @Test
  @DisplayName("an incomplete appearance payload is refused before the backend sees it")
  void refusesAnIncompletePayload() throws Exception {
    mockMvc
        .perform(
            put("/navigation/appearance")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"desktopMode\":\"PINNED\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION"));
  }

  @Test
  @DisplayName("the stored appearance can be re-read after a conflict")
  void rereadsTheAppearance() throws Exception {
    stored(DesktopMode.RAIL, 7);

    mockMvc
        .perform(
            get("/navigation/appearance")
                .with(member())
                .header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.desktopMode").value("RAIL"))
        .andExpect(jsonPath("$.version").value(7));
  }
}
