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

package de.greluc.krt.profit.basetool.frontend.shell.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.shell.model.ScLink;
import de.greluc.krt.profit.basetool.frontend.shell.model.ScLinkCategory;
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
            .andExpect(view().name("shell/sc-links"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("<h1>Star-Citizen-Links</h1>")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Ressourcen<")
        .doesNotContain("hud-box")
        .contains("Offizielles")
        .contains("Handel &amp; Fracht")
        .contains("/css/pages/shell/sc-links.css");
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
  void everyCategoryIsAnOpenToggleControllingItsGridAndTheScriptLoads() throws Exception {
    String html =
        mockMvc
            .perform(get("/sc-links").with(oidcLogin()).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    for (ScLinkCategory category : ScLinkCategory.values()) {
      String key = category.getKey();
      assertThat(html)
          .contains("data-testid=\"sc-links-toggle-" + key + "\"")
          .contains("aria-controls=\"sc-links-grid-" + key + "\"")
          .contains("data-category=\"" + key + "\"")
          .contains("id=\"sc-links-grid-" + key + "\"");
    }
    assertThat(html.split("data-trigger=\"sc-links-toggle-category\"", -1))
        .hasSize(ScLinkCategory.values().length + 1);
    assertThat(html.split("aria-expanded=\"true\"", -1)).hasSizeGreaterThan(1);
    assertThat(html)
        .doesNotContainPattern("class=\"sc-links-grid\"[^>]*hidden")
        .contains("/js/shell/sc-links.js");
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

    assertThat(html)
        .contains("Star Citizen links")
        .contains("Official")
        .contains("Ships &amp; equipment");
  }

  @Test
  void theFooterLinksThePageAndTheQuickAccessIndexesIt() throws Exception {
    String html =
        mockMvc
            .perform(get("/sc-links").with(oidcLogin()).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .doesNotContain("data-group-key=\"resources\"")
        .containsPattern("<a [^>]*href=\"/sc-links\"[^>]*data-testid=\"footer-sc-links\"")
        .containsPattern("<a [^>]*href=\"/sc-links\"[^>]*data-testid=\"nav-sc-links\"");
  }
}
