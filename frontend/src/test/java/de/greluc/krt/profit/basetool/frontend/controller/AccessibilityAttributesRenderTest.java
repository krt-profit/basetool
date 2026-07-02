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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the two page-wide accessibility attributes that {@code AccessibilitySmokeE2eTest} gates on
 * but that only the heavyweight ephemeral-stack e2e run otherwise exercises: the {@code <html
 * lang>} attribute (axe {@code html-has-lang}) and the icon-only hamburger button's {@code
 * aria-label} (axe {@code button-name}). Both live in every standalone page — the hamburger inline
 * in each {@code <header>}, the {@code lang} on each {@code <html>} — so rendering the anonymous
 * {@code GET /} index through the full Thymeleaf pipeline is a representative, fast regression
 * guard that survives in plain {@code :frontend:test} (no Docker, no browser). Assertions are
 * locale-agnostic (regex find, not literal copy) so they hold whichever default locale the test
 * context resolves.
 *
 * <p>Mirrors {@link HomeControllerMvcTest}'s setup: the two collaborators the index render path
 * touches are mocked, and the {@code /api/v1/missions/search} next-7-days lookup is stubbed to
 * {@code null} (the valid "no upcoming missions" response).
 */
@SpringBootTest
class AccessibilityAttributesRenderTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  /** Builds a security-aware MockMvc and stubs the single backend call the index page makes. */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef(), anyBoolean()))
        .thenReturn(null);
  }

  /**
   * The rendered {@code <html>} element must carry a {@code lang} attribute resolving to a known
   * locale code, so axe's {@code html-has-lang} (a {@code serious} rule) stays clear and the page
   * declares its language for assistive tech and search engines.
   *
   * @throws Exception if the request fails or the view cannot be rendered
   */
  @Test
  void index_htmlElement_carriesLangAttribute() throws Exception {
    String html = mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString();

    assertThat(html)
        .as("<html> must declare a lang attribute (axe html-has-lang)")
        .containsPattern("<html[^>]*\\slang=\"(de|en)\"");
  }

  /**
   * The icon-only hamburger button (three bare {@code <span>} bars, no text node) must expose a
   * non-empty {@code aria-label}, so axe's {@code button-name} (a {@code critical} rule) stays
   * clear and screen-reader users get a discernible name for the primary navigation toggle.
   *
   * @throws Exception if the request fails or the view cannot be rendered
   */
  @Test
  void index_hamburgerButton_hasDiscernibleAriaLabel() throws Exception {
    String html = mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString();

    assertThat(html)
        .as("hamburger button must carry a non-empty aria-label (axe button-name)")
        .containsPattern("<button id=\"hamburger\"[^>]*\\saria-label=\"[^\"]+\"");
  }
}
