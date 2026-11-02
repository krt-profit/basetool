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

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.navigation.model.AppearanceResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.DesktopMode;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarsResponse;
import java.util.List;
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
 * The start-page redirect of REQ-UI-034: {@code /start} sends the caller to the start page of the
 * mode, and to the dashboard when none is set, the page is not permitted or not available here, or
 * the preferences could not be read.
 */
@SpringBootTest
class StartPageRedirectMvcTest {

  private static final String PREFERENCES = "/api/v1/navigation/preferences";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private void startPages(String member, String admin) {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of(), 0),
                HiddenResponse.EMPTY,
                OrderResponse.EMPTY,
                new AppearanceResponse(DesktopMode.DRAWER, true, member, admin, 1),
                TabBarsResponse.AUTOMATIC));
  }

  private static RequestPostProcessor member() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private static RequestPostProcessor admin() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  @Test
  @DisplayName("a member without a start page lands on the dashboard")
  void noStartPageGoesToTheDashboard() throws Exception {
    startPages(null, null);

    mockMvc.perform(get("/start").with(member())).andExpect(redirectedUrl("/"));
  }

  @Test
  @DisplayName("the member start page opens, and nothing of the page model leaks into the URL")
  void theMemberStartPageOpens() throws Exception {
    startPages("missions.list", null);

    mockMvc
        .perform(get("/start").with(member()))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/missions"));
  }

  @Test
  @DisplayName("a start page that is a tab opens with its query")
  void aTabStartPageKeepsItsQuery() throws Exception {
    startPages("storage.all", null);

    mockMvc
        .perform(get("/start").with(member()))
        .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/inventory?")));
  }

  @Test
  @DisplayName("a start page the caller may no longer open falls back to the dashboard")
  void aForbiddenStartPageFallsBack() throws Exception {
    startPages("admin.members", null);

    mockMvc.perform(get("/start").with(member())).andExpect(redirectedUrl("/"));
  }

  @Test
  @DisplayName("an unknown or malformed key falls back to the dashboard and never redirects away")
  void anUnknownKeyFallsBack() throws Exception {
    startPages("https://evil.example/", null);
    mockMvc.perform(get("/start").with(member())).andExpect(redirectedUrl("/"));

    startPages("no.such.page", null);
    mockMvc.perform(get("/start").with(member())).andExpect(redirectedUrl("/"));
  }

  @Test
  @DisplayName("an unreadable store falls back to the dashboard")
  void anUnreadableStoreFallsBack() throws Exception {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenThrow(new BackendServiceException("down", null, 503));

    mockMvc.perform(get("/start").with(member())).andExpect(redirectedUrl("/"));
  }

  @Test
  @DisplayName("the member start page is not the administration start page")
  void eachModeUsesItsOwnPage() throws Exception {
    startPages("missions.list", "admin.members");

    mockMvc.perform(get("/start").with(admin())).andExpect(redirectedUrl("/missions"));
    mockMvc
        .perform(get("/start").param("mode", "admin").with(admin()))
        .andExpect(redirectedUrl("/members"));
  }

  @Test
  @DisplayName("an administrator without an administration start page lands on the admin dashboard")
  void adminFallsBackToTheAdminDashboard() throws Exception {
    startPages("missions.list", null);

    mockMvc
        .perform(get("/start").param("mode", "admin").with(admin()))
        .andExpect(redirectedUrl("/admin"));
  }

  @Test
  @DisplayName("a page of the other mode is not a start page of this mode")
  void aPageOfTheOtherModeFallsBack() throws Exception {
    startPages("admin.members", "missions.list");

    mockMvc.perform(get("/start").with(admin())).andExpect(redirectedUrl("/"));
    mockMvc
        .perform(get("/start").param("mode", "admin").with(admin()))
        .andExpect(redirectedUrl("/admin"));
  }

  @Test
  @DisplayName("an unknown mode counts as the member mode")
  void anUnknownModeIsTheMemberMode() throws Exception {
    startPages("missions.list", "admin.members");

    mockMvc
        .perform(get("/start").param("mode", "//evil.example").with(admin()))
        .andExpect(redirectedUrl("/missions"));
  }

  @Test
  @DisplayName("an anonymous visitor is sent to sign in")
  void anonymousVisitorsSignIn() throws Exception {
    mockMvc
        .perform(get("/start"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/oauth2/")));
  }
}
