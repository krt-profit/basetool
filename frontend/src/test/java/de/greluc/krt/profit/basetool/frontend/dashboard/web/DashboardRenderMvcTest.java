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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardColumn;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardMode;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardStatus;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.MeLayoutResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.AppearanceResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarsResponse;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the home page and the dashboard fragments through MockMvc: the board per stored layout,
 * the unavailable and empty states, the one-time hint, the deletion of a lost widget and the bodies
 * of the information, favourites and notifications widgets (REQ-UI-034).
 */
@SpringBootTest
class DashboardRenderMvcTest {

  private static final String DASHBOARD = "/api/v1/navigation/dashboards/{mode}";
  private static final String PREFERENCES = "/api/v1/navigation/preferences";
  private static final String XHR = "X-Requested-With";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static RequestPostProcessor member() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private static RequestPostProcessor admin() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static DashboardWidgetResponse widget(
      DashboardWidgetType type, DashboardColumn column, UUID id, UUID unit) {
    return new DashboardWidgetResponse(
        id,
        type,
        column,
        3,
        unit,
        type == DashboardWidgetType.UPCOMING_DAYS ? 14 : null,
        type == DashboardWidgetType.UPCOMING_DAYS || type == DashboardWidgetType.NOTIFICATIONS
            ? 10
            : null,
        type == DashboardWidgetType.NOTIFICATIONS ? DashboardStatus.ALL : null);
  }

  private void stored(boolean customised, long version, DashboardWidgetResponse... widgets) {
    when(backendApiClient.get(eq(DASHBOARD), eq(DashboardResponse.class), eq("member")))
        .thenReturn(
            new DashboardResponse(DashboardMode.MEMBER, version, customised, List.of(widgets)));
  }

  private String home(RequestPostProcessor user) throws Exception {
    return mockMvc
        .perform(get("/").with(user).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @DisplayName("the default renders both columns with the shells of the four widgets")
  void rendersTheDefaultBoard() throws Exception {
    stored(
        false,
        0,
        widget(DashboardWidgetType.UPCOMING_DAYS, DashboardColumn.WIDE, null, null),
        widget(DashboardWidgetType.INFORMATION, DashboardColumn.WIDE, null, null),
        widget(DashboardWidgetType.FAVOURITES, DashboardColumn.NARROW, null, null),
        widget(DashboardWidgetType.NOTIFICATIONS, DashboardColumn.NARROW, null, null));

    String html = home(member());

    assertThat(html)
        .contains("id=\"dashboard-board\"")
        .contains("data-version=\"0\"")
        .contains("data-customised=\"false\"")
        .contains("data-testid=\"dashboard-widget-upcoming-days\"")
        .contains("data-testid=\"dashboard-widget-information\"")
        .contains("data-testid=\"dashboard-widget-favourites\"")
        .contains("data-testid=\"dashboard-widget-notifications\"")
        .contains("data-testid=\"dashboard-customise\"")
        .contains("data-testid=\"dashboard-hint\"")
        .contains("Nächste 14 Tage");
    assertThat(html.indexOf("data-dashboard-column=\"WIDE\""))
        .isLessThan(html.indexOf("data-dashboard-column=\"NARROW\""));
    assertThat(html.indexOf("dashboard-widget-information"))
        .isLessThan(html.indexOf("data-dashboard-column=\"NARROW\""));
  }

  @Test
  @DisplayName("a widget pinned to another unit than the context renders greyed with the hint")
  void rendersAPinnedWidgetGreyed() throws Exception {
    UUID alpha = UUID.randomUUID();
    UUID beta = UUID.randomUUID();
    stored(
        true,
        2,
        widget(DashboardWidgetType.UPCOMING_DAYS, DashboardColumn.WIDE, UUID.randomUUID(), alpha));
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(LayoutResponses.activeOrgUnit(beta));

    String html = home(member());

    assertThat(html)
        .contains("data-available=\"false\"")
        .contains("data-testid=\"dashboard-widget-unavailable\"")
        .contains("is-unavailable");
  }

  @Test
  @DisplayName("a stored widget whose role the caller lost is deleted and not rendered")
  void deletesAStoredWidgetWhoseRoleIsGone() throws Exception {
    DashboardResponse before =
        new DashboardResponse(
            DashboardMode.MEMBER,
            4,
            true,
            List.of(
                widget(
                    DashboardWidgetType.UPCOMING_DAYS,
                    DashboardColumn.WIDE,
                    UUID.randomUUID(),
                    null),
                widget(
                    DashboardWidgetType.INFORMATION,
                    DashboardColumn.WIDE,
                    UUID.randomUUID(),
                    null)));
    DashboardResponse after =
        new DashboardResponse(
            DashboardMode.MEMBER,
            5,
            true,
            List.of(
                widget(
                    DashboardWidgetType.INFORMATION,
                    DashboardColumn.WIDE,
                    UUID.randomUUID(),
                    null)));
    when(backendApiClient.get(eq(DASHBOARD), eq(DashboardResponse.class), eq("member")))
        .thenReturn(before);
    when(backendApiClient.post(
            eq(DASHBOARD + "/prune"), any(), eq(DashboardResponse.class), eq("member")))
        .thenReturn(after);

    String html = home(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER")));

    assertThat(html)
        .contains("data-version=\"5\"")
        .contains("data-testid=\"dashboard-widget-information\"")
        .doesNotContain("data-testid=\"dashboard-widget-upcoming-days\"");
    verify(backendApiClient)
        .post(eq(DASHBOARD + "/prune"), any(), eq(DashboardResponse.class), eq("member"));
  }

  @Test
  @DisplayName("a customised and emptied dashboard shows the empty state with the add button")
  void rendersTheEmptyState() throws Exception {
    stored(true, 6);

    String html = home(member());

    assertThat(html)
        .contains("data-testid=\"dashboard-empty\"")
        .doesNotContain(
            "class=\"card dash-state dash-empty\" data-dashboard-empty"
                + " data-testid=\"dashboard-empty\" is-hidden")
        .doesNotContain("data-testid=\"dashboard-hint\"")
        .contains("data-trigger=\"dashboard-add\"")
        .contains("Das Dashboard ist leer");
  }

  @Test
  @DisplayName("without a stored dashboard the page shows the error state and no edit controls")
  void rendersTheErrorStateWhenNothingCouldBeRead() throws Exception {
    String html = home(member());

    assertThat(html)
        .contains("data-testid=\"dashboard-load-error\"")
        .doesNotContain("data-testid=\"dashboard-customise\"")
        .doesNotContain("dashboard-catalogue-modal");
  }

  @Test
  @DisplayName("a member with a favourite gets no hint")
  void aFavouriteDropsTheHint() throws Exception {
    stored(false, 0, widget(DashboardWidgetType.FAVOURITES, DashboardColumn.NARROW, null, null));
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of("missions.list"), 1),
                new HiddenResponse(List.of(), 0),
                AppearanceResponse.DRAWER,
                TabBarsResponse.AUTOMATIC));

    assertThat(home(member())).doesNotContain("data-testid=\"dashboard-hint\"");
  }

  @Test
  @DisplayName("the catalogue dialog lists the widgets the caller may add")
  void rendersTheCatalogueOfPermittedWidgets() throws Exception {
    stored(false, 0);

    String html = home(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER")));

    assertThat(html)
        .contains("data-testid=\"dashboard-add-information\"")
        .contains("data-testid=\"dashboard-add-notifications\"")
        .doesNotContain("data-testid=\"dashboard-add-upcoming-days\"")
        .doesNotContain("data-testid=\"dashboard-add-next-mission\"")
        .contains("data-testid=\"dashboard-add-hangar-readiness\"")
        .contains("data-testid=\"dashboard-add-assigned-orders\"")
        .doesNotContain("data-testid=\"dashboard-add-low-stock\"")
        .doesNotContain("data-testid=\"dashboard-add-bank\"");
  }

  @Test
  @DisplayName("a member may add the work widgets, whose options dialog offers their own statuses")
  void offersTheWorkWidgetsWithTheirOptions() throws Exception {
    stored(
        true,
        1,
        widget(
            DashboardWidgetType.ASSIGNED_ORDERS, DashboardColumn.NARROW, UUID.randomUUID(), null),
        widget(DashboardWidgetType.LOW_STOCK, DashboardColumn.NARROW, UUID.randomUUID(), null),
        widget(DashboardWidgetType.BANK, DashboardColumn.NARROW, UUID.randomUUID(), null),
        widget(DashboardWidgetType.NOTIFICATIONS, DashboardColumn.NARROW, UUID.randomUUID(), null));

    String html = home(member());

    assertThat(html)
        .contains("data-testid=\"dashboard-add-assigned-orders\"")
        .contains("data-testid=\"dashboard-add-low-stock\"")
        .contains("data-testid=\"dashboard-add-bank\"")
        .contains("data-widget-slug=\"assigned-orders\"")
        .contains("data-widget-slug=\"low-stock\"")
        .contains("data-widget-slug=\"bank\"")
        .contains("data-status-choices=\"OPEN_ACTIVE OPEN IN_PROGRESS\"")
        .contains("data-status-choices=\"UNREAD ALL\"")
        .contains("data-options=\"unit rows\"")
        .contains("data-options=\"rows\"");
  }

  @Test
  @DisplayName(
      "a member may add the next-mission and hangar-readiness widgets, which have no options")
  void offersTheSummaryWidgetsWithoutAnOptionsButton() throws Exception {
    stored(
        true,
        1,
        widget(DashboardWidgetType.NEXT_MISSION, DashboardColumn.NARROW, UUID.randomUUID(), null),
        widget(
            DashboardWidgetType.HANGAR_READINESS, DashboardColumn.NARROW, UUID.randomUUID(), null));

    String html = home(member());

    assertThat(html)
        .contains("data-testid=\"dashboard-add-next-mission\"")
        .contains("data-testid=\"dashboard-add-hangar-readiness\"")
        .contains("data-testid=\"dashboard-widget-next-mission\"")
        .contains("data-testid=\"dashboard-widget-hangar-readiness\"")
        .contains("data-widget-slug=\"next-mission\"")
        .contains("data-widget-slug=\"hangar-readiness\"")
        .doesNotContain("data-testid=\"dashboard-options\"");
  }

  @Test
  @DisplayName("the board fragment is served only to a background request")
  void theBoardFragmentNeedsTheBackgroundMarker() throws Exception {
    stored(false, 0, widget(DashboardWidgetType.FAVOURITES, DashboardColumn.NARROW, null, null));

    String fragment =
        mockMvc
            .perform(get("/dashboard/member/board").header(XHR, "XMLHttpRequest").with(member()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(fragment)
        .startsWith("<div")
        .contains("id=\"dashboard-board\"")
        .doesNotContain("<html");
    mockMvc
        .perform(get("/dashboard/member/board").with(member()))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("the favourites widget lists the favourites and explains the empty case")
  void rendersTheFavouritesWidget() throws Exception {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of(), 0),
                new HiddenResponse(List.of(), 0),
                AppearanceResponse.DRAWER,
                TabBarsResponse.AUTOMATIC));
    assertThat(widget("favourites"))
        .contains("data-testid=\"dashboard-favourites-empty\"")
        .contains("Du hast noch keine Favoriten");

    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of("missions.list"), 1),
                new HiddenResponse(List.of(), 0),
                AppearanceResponse.DRAWER,
                TabBarsResponse.AUTOMATIC));
    assertThat(widget("favourites"))
        .contains("data-entry-key=\"missions.list\"")
        .doesNotContain("dashboard-favourites-empty");
  }

  @Test
  @DisplayName("the notifications widget carries its options for the script")
  void rendersTheNotificationsShell() throws Exception {
    String html =
        mockMvc
            .perform(
                get("/dashboard/widgets/notifications")
                    .param("rows", "10")
                    .param("status", "ALL")
                    .header(XHR, "XMLHttpRequest")
                    .with(member()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-notification-rows=\"10\"")
        .contains("data-notification-status=\"ALL\"")
        .contains("data-home-notif-list");
  }

  @Test
  @DisplayName("the information widget offers the inline editor to an administrator only")
  void offersTheInlineEditorToAdministrators() throws Exception {
    assertThat(widget("information", member()))
        .doesNotContain("data-testid=\"dashboard-info-edit\"");
    assertThat(widget("information", admin())).contains("data-testid=\"dashboard-info-edit\"");
    verify(backendApiClient, org.mockito.Mockito.atLeastOnce())
        .get(
            any(String.class),
            eq(de.greluc.krt.profit.basetool.frontend.dashboard.model.AnnouncementDto.class));
  }

  private String widget(String slug) throws Exception {
    return widget(slug, member());
  }

  private String widget(String slug, RequestPostProcessor user) throws Exception {
    return mockMvc
        .perform(
            get("/dashboard/widgets/" + slug)
                .header(XHR, "XMLHttpRequest")
                .with(user)
                .locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
