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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the full {@code index} view, including the sidebar and toast fragments, through MockMvc
 * so a broken SpEL expression in the toast fragment fails a test instead of returning 500. Uses
 * {@code ?error=} and {@code ?success=} parameters to exercise the toast's {@code matches}
 * branches.
 */
@SpringBootTest
class HomeControllerMvcTest {

  private static final String ERROR_TOAST_ID = "errorNotificationParam";
  private static final String SUCCESS_TOAST_ID = "successNotificationParam";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    when(backendApiClient.get(startsWith("/api/v1/missions"), anyTypeRef())).thenReturn(null);
  }

  @Test
  void home_ShouldRenderIndex_WithoutQueryParams() throws Exception {
    mockMvc
        .perform(get("/").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(view().name("dashboard/index"));
  }

  /**
   * An {@code ?error=} value matching the key pattern renders the page with the parameter toast.
   */
  @Test
  void home_ShouldRenderIndex_WhenErrorParamMatchesKeyPattern() throws Exception {
    mockMvc
        .perform(get("/").with(oidcLogin()).param("error", "notification.error.title"))
        .andExpect(status().isOk())
        .andExpect(view().name("dashboard/index"))
        .andExpect(content().string(containsString(ERROR_TOAST_ID)));
  }

  /**
   * A {@code ?success=} value matching the key pattern renders the page with the parameter toast.
   */
  @Test
  void home_ShouldRenderIndex_WhenSuccessParamMatchesKeyPattern() throws Exception {
    mockMvc
        .perform(get("/").with(oidcLogin()).param("success", "notification.success.title"))
        .andExpect(status().isOk())
        .andExpect(view().name("dashboard/index"))
        .andExpect(content().string(containsString(SUCCESS_TOAST_ID)));
  }

  /**
   * The regex gate exists to prevent arbitrary translation-key enumeration via {@code
   * ?error=any.key}; a value that fails the pattern must simply skip the toast div (no crash, no
   * leakage). This test pins both halves of the contract: (a) the request does not 500, and (b) the
   * param-error toast is NOT rendered.
   */
  @Test
  void home_ShouldRenderIndex_WithoutParamErrorToast_WhenErrorParamFailsKeyPattern()
      throws Exception {
    mockMvc
        .perform(get("/").with(oidcLogin()).param("error", "9 invalid value with spaces"))
        .andExpect(status().isOk())
        .andExpect(view().name("dashboard/index"))
        .andExpect(content().string(not(containsString(ERROR_TOAST_ID))));
  }

  /**
   * Empty {@code ?success=} satisfies {@code param.success != null} (the param is present, just
   * empty) but fails the key pattern (which requires a leading letter). Must not crash and must not
   * emit the success-param toast.
   */
  @Test
  void home_ShouldRenderIndex_WithoutParamSuccessToast_WhenSuccessParamIsEmpty() throws Exception {
    mockMvc
        .perform(get("/").with(oidcLogin()).param("success", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("dashboard/index"))
        .andExpect(content().string(not(containsString(SUCCESS_TOAST_ID))));
  }

  /**
   * The landing page renders no data, calls no backend and creates no session (REQ-SEC-052).
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @org.springframework.security.test.context.support.WithAnonymousUser
  void anonymousRootRendersTheLandingPageWithoutDataOrSession() throws Exception {
    org.springframework.test.web.servlet.MvcResult result =
        mockMvc
            .perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/landing"))
            .andReturn();

    org.assertj.core.api.Assertions.assertThat(result.getRequest().getSession(false))
        .as("the landing page must not mint a session — REQ-SEC-025 counts every one of these")
        .isNull();
    org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeaders("Set-Cookie"))
        .as("nor set a session cookie")
        .noneMatch(header -> header.startsWith("__Host-SESSION=") || header.startsWith("SESSION="));
    org.mockito.Mockito.verifyNoInteractions(backendApiClient);
  }

  /**
   * The landing page shows a logged-out visitor no navigation, notification bell or org-unit
   * switcher; the sidebar's {@code sec:authorize} guards are the only protection on this public
   * page (REQ-SEC-052).
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @org.springframework.security.test.context.support.WithAnonymousUser
  void anonymousLandingPageShowsNoNavigation() throws Exception {
    String html =
        mockMvc
            .perform(get("/"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.assertj.core.api.Assertions.assertThat(html)
        .as("the notification bell is behind sec:authorize and must not render")
        .doesNotContain("id=\"notification-bell\"");
    org.assertj.core.api.Assertions.assertThat(html)
        .as("nor any of the tool's own destinations")
        .doesNotContain("/missions")
        .doesNotContain("/inventory")
        .doesNotContain("/orders")
        .doesNotContain("/hangar");
    org.assertj.core.api.Assertions.assertThat(html)
        .as("what it DOES carry: the two login entry points and the legal pages")
        .contains("/oauth2/authorization/keycloak")
        .contains("/impressum")
        .contains("/privacy")
        .contains("/licenses");
  }
}
