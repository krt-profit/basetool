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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.MeLayoutResponse;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.List;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the member default of REQ-UI-026: the four task groups of the drawer, the footer link to
 * the Star Citizen links and the tab bars of pages that merged their siblings.
 */
@SpringBootTest
class NavigationMemberRenderMvcTest {

  private static final Pattern SIDEBAR =
      Pattern.compile("<div id=\"sidebar\".*?<div id=\"sidebar-overlay\"", Pattern.DOTALL);

  private static final Pattern TAB_ENTRY_KEY =
      Pattern.compile("<a [^>]*class=\"tab[ a-z]*\"[^>]*data-entry-key=\"([^\"]+)\"");

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
   * A member sees the four task groups in their default order, with neither „Startseite", the bell
   * link, the tabs' own pages nor a Ressourcen group in the drawer.
   *
   * @throws Exception if the page cannot be rendered
   */
  @Test
  void memberDrawerHasTheFourTaskGroups() throws Exception {
    String sidebar = section(render("/impressum"));

    assertThat(sidebar)
        .containsSubsequence(
            "data-group-key=\"operations\"",
            "data-group-key=\"logistics\"",
            "data-group-key=\"trade\"",
            "data-group-key=\"unit\"")
        .doesNotContain("data-group-key=\"resources\"")
        .doesNotContain("data-group-key=\"supply\"")
        .doesNotContain("data-group-key=\"promotion\"")
        .doesNotContain("data-group-key=\"missions\"")
        .doesNotContain("data-entry-key=\"home.dashboard\"")
        .doesNotContain("data-entry-key=\"notifications.list\"")
        .doesNotContain("data-entry-key=\"operations.list\"")
        .doesNotContain("data-entry-key=\"hangar.catalog\"")
        .doesNotContain("data-entry-key=\"storage.levels\"")
        .doesNotContain("data-entry-key=\"orgunitbank.overview\"")
        .contains("data-entry-key=\"missions.list\"")
        .contains("data-entry-key=\"hangar.overview\"")
        .contains("data-entry-key=\"storage.overview\"")
        .contains("data-entry-key=\"refinery.orders\"")
        .contains("data-entry-key=\"materials.list\"")
        .contains("data-entry-key=\"materialboerse.board\"")
        .contains("data-entry-key=\"orgchart.view\"");
  }

  /**
   * The Star Citizen links are a footer link for a signed-in user, and the drawer no longer lists
   * them.
   *
   * @throws Exception if the page cannot be rendered
   */
  @Test
  void starCitizenLinksSitInTheFooter() throws Exception {
    String html = render("/impressum");

    assertThat(html)
        .containsPattern("<a [^>]*href=\"/sc-links\"[^>]*data-testid=\"footer-sc-links\"")
        .contains("data-entry-key=\"sclinks.list\"");
    assertThat(section(html)).doesNotContain("data-testid=\"nav-sc-links\"");
  }

  /**
   * The Leitung tab sits inside the Organisation page and shows only with the officer-reach flag.
   *
   * @throws Exception if the page cannot be rendered
   */
  @Test
  void theLeitungTabShowsOnlyWithTheFlag() throws Exception {
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(LayoutResponses.officerReach(false));
    List<String> without = tabKeys(render("/org-chart"));
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(LayoutResponses.officerReach(true));
    List<String> with = tabKeys(render("/org-chart"));

    assertThat(without).contains("orgchart.view", "orgunitbank.overview");
    assertThat(without).doesNotContain("organisation.leadership");
    assertThat(with).contains("orgchart.view", "orgunitbank.overview", "organisation.leadership");
  }

  @NotNull
  private String render(@NotNull String path) throws Exception {
    return mockMvc
        .perform(
            get(path).with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @NotNull
  private static String section(@NotNull String html) {
    Matcher matcher = SIDEBAR.matcher(html);
    assertThat(matcher.find()).as("rendered page contains the drawer").isTrue();
    return matcher.group();
  }

  @NotNull
  private static List<String> tabKeys(@NotNull String html) {
    return TAB_ENTRY_KEY.matcher(html).results().map(match -> match.group(1)).toList();
  }
}
