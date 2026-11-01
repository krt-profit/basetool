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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardColumn;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardMode;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders admin mode: the dashboard link, three collapsed groups with 13 links, the admin dashboard
 * with its hub widget and the redirect of the former bank page (REQ-UI-026).
 */
@SpringBootTest
class AdminNavigationRenderMvcTest {

  private static final Pattern ADMIN_MODE =
      Pattern.compile(
          "<div class=\"nav-mode nav-mode-admin\".*?<p class=\"nav-filter-empty\"", Pattern.DOTALL);

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  /** Builds a security-aware MockMvc. */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Admin mode renders the hub link and three groups that start collapsed, holding 13 links.
   *
   * @throws Exception if the page cannot be rendered
   */
  @Test
  void adminModeIsTheHubPlusThirteenLinksInThreeCollapsedGroups() throws Exception {
    String html =
        mockMvc
            .perform(
                get("/impressum")
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Matcher matcher = ADMIN_MODE.matcher(html);
    assertThat(matcher.find()).isTrue();
    String admin = matcher.group();

    assertThat(admin)
        .contains("data-entry-key=\"admin.dashboard\"")
        .contains("data-group-key=\"admin-members\"")
        .contains("data-group-key=\"admin-masterdata\"")
        .contains("data-group-key=\"admin-operations\"")
        .doesNotContain("data-group-key=\"admin-bank\"")
        .doesNotContain("data-group-key=\"admin-system\"");
    assertThat(
            Pattern.compile("<section class=\"nav-group[^\"]*\"[^>]*data-collapsed=\"true\"")
                .matcher(admin)
                .results()
                .count())
        .isEqualTo(3);
    assertThat(Pattern.compile("<a [^>]*class=\"nav-link\"").matcher(admin).results().count())
        .isEqualTo(13);
  }

  /**
   * The admin dashboard is the board of the admin mode and a non-admin cannot reach it.
   *
   * @throws Exception if the page cannot be rendered
   */
  @Test
  void adminDashboardIsForAdministratorsOnly() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/navigation/dashboards/{mode}"), eq(DashboardResponse.class), eq("admin")))
        .thenReturn(
            new DashboardResponse(
                DashboardMode.ADMIN,
                0,
                false,
                List.of(
                    new DashboardWidgetResponse(
                        null,
                        DashboardWidgetType.ADMIN_HUB,
                        DashboardColumn.WIDE,
                        0,
                        null,
                        null,
                        null,
                        null))));

    String html =
        mockMvc
            .perform(
                get("/admin")
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html)
        .contains("data-testid=\"dashboard-board\"")
        .contains("data-mode=\"admin\"")
        .contains("data-widget-type=\"ADMIN_HUB\"")
        .contains("data-testid=\"dashboard-customise\"");

    mockMvc
        .perform(
            get("/admin")
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());
  }

  /**
   * The hub widget lists the groups of admin links and is for administrators only.
   *
   * @throws Exception if the fragment cannot be rendered
   */
  @Test
  void hubWidgetListsTheLinksForAdministratorsOnly() throws Exception {
    String html =
        mockMvc
            .perform(
                get("/dashboard/widgets/admin-hub")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html)
        .contains("data-testid=\"admin-hub\"")
        .contains("href=\"/admin/audit-log\"")
        .contains("href=\"/admin/person-search\"")
        .doesNotContain("/admin/announcement");

    mockMvc
        .perform(
            get("/dashboard/widgets/admin-hub")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());
  }

  /**
   * Merged pages show their tab bar with the current tab marked.
   *
   * @throws Exception if the page cannot be rendered
   */
  @Test
  void dangerZoneTabIsMarkedOnTheSystemSettingsTabBar() throws Exception {
    String html =
        mockMvc
            .perform(
                get("/admin/settings/danger-zone")
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html)
        .contains("data-testid=\"page-tabs\"")
        .containsPattern(
            "class=\"tab"
                + " active\"[^>]*data-entry-key=\"admin.dangerzone\"|data-entry-key=\"admin.dangerzone\"[^>]*class=\"tab"
                + " active\"")
        .contains("data-entry-key=\"admin.supply\"");
  }

  /**
   * The former bank page redirects to its page and tab.
   *
   * @throws Exception if the request fails
   */
  @Test
  void formerBankPageRedirectsToTheDangerZoneTab() throws Exception {
    mockMvc
        .perform(
            get("/admin/bank")
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/settings/danger-zone"));
  }
}
