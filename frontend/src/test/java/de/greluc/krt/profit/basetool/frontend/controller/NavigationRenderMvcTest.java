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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
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
 * Renders the shared navigation chrome (header, drawer, quick access, mobile tab bar) for an
 * administrator, a member and an anonymous visitor and checks the information architecture of
 * REQ-UI-026.
 */
@SpringBootTest
class NavigationRenderMvcTest {

  private static final Pattern SIDEBAR =
      Pattern.compile("<div id=\"sidebar\".*?<div id=\"sidebar-overlay\"", Pattern.DOTALL);

  private static final Pattern HEADER = Pattern.compile("<header>.*?</header>", Pattern.DOTALL);

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  /** Builds a security-aware MockMvc; the static legal page needs no backend stubs. */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * An administrator gets the quick-access trigger and the bell in the header, a closed and inert
   * drawer with the menu filter, the main groups, the four administration groups behind the
   * administration entry, the user row with logout, the palette dialog and the mobile tab bar.
   *
   * @throws Exception if the page cannot be rendered
   */
  @Test
  void administratorGetsTheFullNavigation() throws Exception {
    String html = render(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
    String header = section(HEADER, html);
    String sidebar = section(SIDEBAR, html);

    assertThat(header)
        .contains("data-testid=\"palette-open\"")
        .contains("data-testid=\"mobile-search-open\"")
        .contains("id=\"notification-bell\"")
        .contains("id=\"hamburger\"");
    assertThat(sidebar)
        .startsWith("<div id=\"sidebar\" class=\"sidebar\" inert")
        .contains("data-testid=\"nav-filter\"")
        .contains("data-group-key=\"missions\"")
        .contains("data-group-key=\"logistics\"")
        .contains("data-group-key=\"trade\"")
        .contains("data-group-key=\"organisation\"")
        .contains("data-group-key=\"resources\"")
        .contains("data-group-key=\"admin-members\"")
        .contains("data-group-key=\"admin-masterdata\"")
        .contains("data-group-key=\"admin-bank\"")
        .contains("data-group-key=\"admin-system\"")
        .contains("data-testid=\"nav-admin-toggle\"")
        .contains("data-testid=\"nav-user-toggle\"")
        .contains("data-testid=\"nav-logout\"")
        .doesNotContain("data-group-key=\"legal\"");
    assertThat(html)
        .contains("id=\"nav-palette\"")
        .contains("data-testid=\"palette-input\"")
        .contains("data-testid=\"mobile-tab-menu\"")
        .contains("/js/krt-palette.js");
  }

  /**
   * Every main group carries the breadcrumb and icon the quick access indexes, and every
   * administration group prefixes its breadcrumb with the admin crumb.
   *
   * @throws Exception if the page cannot be rendered
   */
  @Test
  void groupsCarryTheirQuickAccessMetadata() throws Exception {
    String sidebar =
        section(SIDEBAR, render(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))));

    Matcher groups = Pattern.compile("<section class=\"nav-group[^\"]*\"[^>]*>").matcher(sidebar);
    int count = 0;
    while (groups.find()) {
      String tag = groups.group();
      assertThat(tag).as(tag).contains("data-nav-icon=\"").contains("data-nav-group=\"");
      if (tag.contains("data-group-key=\"admin-")) {
        assertThat(tag).as(tag).contains("data-nav-group=\"Admin · ");
      }
      count++;
    }
    assertThat(count).as("main, personal and admin groups").isGreaterThanOrEqualTo(10);
  }

  /**
   * A member without the administrator role gets no administration groups and no entry to them, but
   * keeps the personal menu and the quick access.
   *
   * @throws Exception if the page cannot be rendered
   */
  @Test
  void memberGetsNoAdministration() throws Exception {
    String html = render(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    String sidebar = section(SIDEBAR, html);

    assertThat(sidebar)
        .doesNotContain("nav-mode-admin")
        .doesNotContain("data-testid=\"nav-admin-toggle\"")
        .doesNotContain("/admin/audit-log")
        .contains("data-testid=\"nav-user-toggle\"")
        .contains("/personal-inventory");
    assertThat(html).contains("id=\"nav-palette\"");
  }

  /**
   * An anonymous visitor of a public legal page gets the login links, but no quick access, no tab
   * bar, no bell and no menu filter.
   *
   * @throws Exception if the page cannot be rendered
   */
  @Test
  void anonymousVisitorGetsOnlyTheLoginLinks() throws Exception {
    String html = render(anonymous());
    String sidebar = section(SIDEBAR, html);

    assertThat(sidebar)
        .contains("data-testid=\"nav-login\"")
        .doesNotContain("data-testid=\"nav-filter\"")
        .doesNotContain("data-testid=\"nav-logout\"");
    assertThat(html)
        .doesNotContain("id=\"nav-palette\"")
        .doesNotContain("mobile-tabbar")
        .doesNotContain("id=\"notification-bell\"")
        .doesNotContain("data-testid=\"palette-open\"");
  }

  @NotNull
  private String render(@NotNull RequestPostProcessor user) throws Exception {
    return mockMvc
        .perform(get("/impressum").with(user))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @NotNull
  private static String section(@NotNull Pattern pattern, @NotNull String html) {
    Matcher matcher = pattern.matcher(html);
    assertThat(matcher.find()).as("rendered page contains " + pattern.pattern()).isTrue();
    return matcher.group();
  }
}
