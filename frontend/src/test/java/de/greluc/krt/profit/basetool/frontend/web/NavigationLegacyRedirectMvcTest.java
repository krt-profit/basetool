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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
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
 * Every page URL that merged into another page reaches that page and tab, and only the page request
 * is redirected (REQ-UI-026).
 */
@SpringBootTest
class NavigationLegacyRedirectMvcTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  /** Builds a security-aware MockMvc. */
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

  /**
   * The former bank page leads to the Gefahrenzone tab of the system settings.
   *
   * @throws Exception if the request fails
   */
  @Test
  void bankPageRedirectsToTheDangerZoneTab() throws Exception {
    mockMvc
        .perform(get("/admin/bank").with(admin()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/settings/danger-zone"));
  }

  /**
   * The Staffeln list of the mission data leads to the Staffeln tab of the organisation structure.
   *
   * @throws Exception if the request fails
   */
  @Test
  void squadronKindOfTheMissionDataRedirectsToTheSquadronsTab() throws Exception {
    mockMvc
        .perform(get("/admin/mission-data").param("kind", "squadrons").with(admin()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/org-structure/squadrons"));
  }

  /**
   * The member picker pages lead to the member list when no member is named, since the member is
   * now chosen there.
   *
   * @throws Exception if the request fails
   */
  @Test
  void personalPagesWithoutAMemberLeadToTheMemberList() throws Exception {
    for (String path : new String[] {"/admin/personal-inventory", "/admin/personal-blueprints"}) {
      mockMvc
          .perform(get(path).with(admin()))
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/members"));
      mockMvc
          .perform(get(path).param("userSub", "not-a-uuid").with(admin()))
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/members"));
    }
  }

  /**
   * The in-place request of a page is not redirected, so the XHR sub-paths keep working.
   *
   * @throws Exception if the request fails
   */
  @Test
  void inPlaceRequestsAreNotRedirected() throws Exception {
    int statusCode =
        mockMvc
            .perform(get("/admin/bank").header("X-Requested-With", "XMLHttpRequest").with(admin()))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(statusCode / 100).isNotEqualTo(3);
  }

  /**
   * A member reaches none of the redirects.
   *
   * @throws Exception if the request fails
   */
  @Test
  void membersAreRefused() throws Exception {
    mockMvc
        .perform(
            get("/admin/bank")
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());
  }

  /**
   * The former Lager list pages lead to the Lager tab with the option selected.
   *
   * @throws Exception if the request fails
   */
  @Test
  void storageListPagesRedirectToTheLagerTabWithTheOption() throws Exception {
    mockMvc
        .perform(get("/inventory/my").with(member()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/inventory?storage=my"));
    mockMvc
        .perform(get("/inventory/all").with(member()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/inventory?storage=all"));
  }

  /**
   * The filter query of a bookmarked list survives the redirect, and a stale option is replaced.
   *
   * @throws Exception if the request fails
   */
  @Test
  void storageListRedirectsKeepTheFilterQuery() throws Exception {
    mockMvc
        .perform(
            get("/inventory/all")
                .param("view", "items")
                .param("gameItemIds", "a", "b")
                .param("storage", "my")
                .with(member()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/inventory?storage=all&view=items&gameItemIds=a&gameItemIds=b"));
  }

  /**
   * Only the page request of a list is redirected; its in-place request and its sub-paths are
   * served as before.
   *
   * @throws Exception if the request fails
   */
  @Test
  void storageListInPlaceRequestsAndSubPathsAreNotRedirected() throws Exception {
    int inPlace =
        mockMvc
            .perform(
                get("/inventory/my").header("X-Requested-With", "XMLHttpRequest").with(member()))
            .andReturn()
            .getResponse()
            .getStatus();
    int subPath =
        mockMvc
            .perform(get("/inventory/my/entry-ids").with(member()))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(inPlace / 100).isNotEqualTo(3);
    assertThat(subPath / 100).isEqualTo(2);
  }
}
