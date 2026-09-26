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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.ScLink;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The „Star-Citizen-Links" page (REQ-UI-025): rendered for every signed-in account with one card
 * per link, and reachable from the sidebar's „Ressourcen" group.
 */
@SpringBootTest
class ScLinksPageControllerMvcTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void rendersEveryLinkAsAnExternalCardWithItsLocalLogo() throws Exception {
    String html =
        mockMvc
            .perform(get("/sc-links").with(oidcLogin()).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andExpect(view().name("sc-links"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("Star-Citizen-Links")
        .contains("Handel &amp; Fracht")
        .contains("/css/pages/sc-links.css");
    for (ScLink link : ScLink.values()) {
      assertThat(html)
          .contains("data-testid=\"sc-link-" + link.getKey() + "\"")
          .contains("href=\"" + link.getUrl() + "\"")
          .contains("src=\"/images/sc-links/" + link.getIcon() + "\"");
    }
    assertThat(html.split("rel=\"noopener noreferrer\"", -1))
        .hasSizeGreaterThan(ScLink.values().length);
  }

  @Test
  void rendersTheEnglishCopy() throws Exception {
    String html =
        mockMvc
            .perform(get("/sc-links").with(oidcLogin()).param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("Star Citizen links").contains("Ships &amp; equipment");
  }

  @Test
  void theSidebarLinksThePageUnderResources() throws Exception {
    String html =
        mockMvc
            .perform(get("/sc-links").with(oidcLogin()).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    int group = html.indexOf("data-group-key=\"resources\"");
    assertThat(group).isPositive();
    String groupHtml = html.substring(group, html.indexOf("</details>", group));
    assertThat(groupHtml)
        .contains("Ressourcen")
        .contains("href=\"/sc-links\"")
        .contains("data-testid=\"nav-sc-links\"");
  }
}
