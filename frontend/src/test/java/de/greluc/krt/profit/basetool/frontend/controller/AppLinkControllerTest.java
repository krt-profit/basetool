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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tests the web-side fallback of the Android App Link (REQ-SEC-038): {@code /app/callback} must not
 * 404, must drop the authorization code from the URL and must reach a page that renders without a
 * session.
 */
@SpringBootTest
@DisplayName("Android App Link fallback")
class AppLinkControllerTest {

  /** Shaped like a real Keycloak callback: the code is what must not survive the redirect. */
  private static final String CALLBACK_WITH_CODE =
      "/app/callback?state=SW_rrSpJFvohrMSGeDW7oCsA98vbbaO&session_state=NNk2aZWQlqLWXtbGEu8uo4K0"
          + "&iss=https%3A%2F%2Fprofit-base.online%2Fauth%2Frealms%2Firi"
          + "&code=9fc63679-91d2-58a8-e2c4-78fbee69fe0b.NNk2aZWQlqLWXtbGEu8uo4K0.7cde253e";

  @Autowired private WebApplicationContext context;

  /** Keeps the real client registration, which performs OIDC discovery, out of the test context. */
  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  /**
   * Builds a MockMvc that runs the real security filter chain.
   *
   * @return the configured MockMvc.
   */
  private MockMvc mvc() {
    return MockMvcBuilders.webAppContextSetup(context)
        .apply(
            org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                .springSecurity())
        .build();
  }

  @Test
  @DisplayName("the callback redirects anonymously instead of 404-ing mid-login")
  void callbackRedirectsAnonymously() throws Exception {
    mvc()
        .perform(get(CALLBACK_WITH_CODE))
        .andExpect(status().isSeeOther())
        .andExpect(redirectedUrl("/app/link-help"));
  }

  @Test
  @DisplayName("the redirect target carries no part of the authorization code")
  void redirectDropsTheAuthorizationCode() throws Exception {
    String location =
        mvc().perform(get(CALLBACK_WITH_CODE)).andReturn().getResponse().getRedirectedUrl();

    Assertions.assertThat(location)
        .as("the redirect must not carry the query string onward")
        .isEqualTo("/app/link-help")
        .doesNotContain("code=", "state=", "session_state=");
  }

  @Test
  @DisplayName("the help page renders for someone with no session")
  void helpPageRendersAnonymously() throws Exception {
    mvc()
        .perform(get("/app/link-help"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("app-link-android")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("app-link-back")));
  }

  @Test
  @DisplayName("the help page resolves its text, rather than printing the keys")
  void helpPageResolvesItsMessages() throws Exception {
    String body =
        mvc().perform(get("/app/link-help")).andReturn().getResponse().getContentAsString();

    Assertions.assertThat(body)
        .as("every appLink.* key must resolve in the default bundle")
        .doesNotContain("??appLink.");
  }
}
