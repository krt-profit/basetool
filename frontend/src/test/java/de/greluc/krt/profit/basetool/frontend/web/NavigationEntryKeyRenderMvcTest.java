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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.navigation.web.NavigationRegistry;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Checks that every link the navigation renders — drawer, phone tab bar and the quick access's own
 * pages and actions — carries the registry key of its entry (REQ-UI-026), for every audience.
 */
@SpringBootTest
class NavigationEntryKeyRenderMvcTest {

  private static final Pattern NAVIGATION =
      Pattern.compile("<div id=\"sidebar\".*?/js/kernel/sidebar\\.js", Pattern.DOTALL);

  private static final Pattern LANGUAGE_MENU =
      Pattern.compile("<div class=\"nav-lang-menu\".*?</div>", Pattern.DOTALL);

  private static final Pattern LINK = Pattern.compile("<a\\s[^>]*>", Pattern.DOTALL);

  private static final Pattern KEY = Pattern.compile("data-entry-key=\"([^\"]+)\"");

  @Autowired private WebApplicationContext context;

  @Autowired private NavigationRegistry registry;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  /** Builds a security-aware MockMvc; the static legal page needs no backend stubs. */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Administrators, members, logisticians, bank staff and anonymous visitors only get links that
   * name a registered entry; the language menu is a control, not an entry.
   *
   * @throws Exception if the page cannot be rendered
   */
  @Test
  void everyRenderedLinkNamesARegisteredEntry() throws Exception {
    List<RequestPostProcessor> audiences =
        List.of(
            oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")),
            oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")),
            oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")),
            oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_BANK_MANAGEMENT")),
            anonymous());
    int links = 0;
    for (RequestPostProcessor audience : audiences) {
      for (String link : links(render(audience))) {
        Matcher key = KEY.matcher(link);
        assertThat(key.find()).as(link).isTrue();
        assertThat(registry.entry(key.group(1))).as(link).isPresent();
        links++;
      }
    }
    assertThat(links).as("links across all audiences").isGreaterThan(150);
  }

  /**
   * One menu link per entry is marked with its key, so a key is a stable handle on the rendered
   * link.
   *
   * @throws Exception if the page cannot be rendered
   */
  @Test
  void menuLinksCarryTheirEntryKey() throws Exception {
    String html = render(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));

    assertThat(html)
        .containsPattern("<a href=\"/missions\"[^>]*data-entry-key=\"missions\\.list\"")
        .containsPattern("<a href=\"/missions\"[^>]*data-testid=\"mobile-tab-missions\"")
        .containsPattern("<a href=\"/inventory/my\"[^>]*data-entry-key=\"storage\\.my\"")
        .containsPattern("<a href=\"/impressum\"[^>]*data-entry-key=\"legal\\.impressum\"");
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
  private static List<String> links(@NotNull String html) {
    Matcher navigation = NAVIGATION.matcher(html);
    assertThat(navigation.find()).as("rendered page contains the navigation").isTrue();
    String scope = LANGUAGE_MENU.matcher(navigation.group()).replaceAll("");
    List<String> links = new ArrayList<>();
    Matcher link = LINK.matcher(scope);
    while (link.find()) {
      links.add(link.group());
    }
    return links;
  }
}
