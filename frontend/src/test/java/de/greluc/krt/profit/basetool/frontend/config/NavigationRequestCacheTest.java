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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that only navigations are saved in the request cache, so a logged-out tab's background
 * calls create no sessions, and that saving and replaying use the same cache (REQ-SEC-025).
 */
@SpringBootTest
class NavigationRequestCacheTest {

  /** Any authenticated path; the refusal is what creates (or does not create) the session. */
  private static final String PROTECTED_PATH = "/missions";

  @Autowired private WebApplicationContext context;
  @Autowired private RequestCache navigationRequestCache;

  /** Mocked so the chain is exercised rather than the backend. */
  @MockitoBean
  private de.greluc.krt.profit.basetool.frontend.service.BackendApiClient backendApiClient;

  /** The frontend is an OAuth2 client; the registry is what the entry point redirects through. */
  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void aNavigationIsSaved_soTheMemberLandsWhereTheyWereGoing() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get(PROTECTED_PATH)
                    .header("Sec-Fetch-Mode", "navigate")
                    .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE))
            .andReturn();

    HttpSession session = result.getRequest().getSession(false);
    assertThat(session).as("a navigation needs somewhere to keep its target").isNotNull();
    assertThat(navigationRequestCache.getRequest(result.getRequest(), result.getResponse()))
        .as("and the target is the page the member asked for")
        .isNotNull()
        .satisfies(
            saved ->
                assertThat(saved.getRedirectUrl())
                    .endsWith(PROTECTED_PATH + "?continue")
                    .contains(PROTECTED_PATH));
  }

  @Test
  void aBackgroundCallIsNotSaved_andMintsNoSession() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get(PROTECTED_PATH)
                    .header("Sec-Fetch-Mode", "cors")
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
            .andReturn();

    assertThat(navigationRequestCache.getRequest(result.getRequest(), result.getResponse()))
        .as("nobody will ever be redirected to a background fetch")
        .isNull();
    assertThat(result.getRequest().getSession(false))
        .as("and holding nothing needs no session — REQ-SEC-025's leak")
        .isNull();
  }

  @Test
  void aBackgroundCallThatAlsoAsksForHtmlIsNotSaved() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get(PROTECTED_PATH)
                    .header("Sec-Fetch-Mode", "cors")
                    .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE))
            .andReturn();

    assertThat(navigationRequestCache.getRequest(result.getRequest(), result.getResponse()))
        .as("a declared background call is a background call, whatever it wants back")
        .isNull();
    assertThat(result.getRequest().getSession(false)).as("and it mints no session either").isNull();
  }

  @Test
  void aClientWithoutFetchMetadataFallsBackToTheAcceptHeader() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get(PROTECTED_PATH).header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE))
            .andReturn();

    assertThat(navigationRequestCache.getRequest(result.getRequest(), result.getResponse()))
        .as("no Fetch Metadata and an HTML Accept is the best evidence of a navigation there is")
        .isNotNull();
  }

  @Test
  void aMonitoringProbeIsNotSaved_soTheProbeMintsNoSession() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get(PROTECTED_PATH)
                    .header("Sec-Fetch-Mode", "navigate")
                    .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                    .header("X-Basetool-Probe", "members-only"))
            .andReturn();

    assertThat(navigationRequestCache.getRequest(result.getRequest(), result.getResponse()))
        .as("nobody logs in after a probe, so there is nothing to send it back to")
        .isNull();
    assertThat(result.getRequest().getSession(false))
        .as("and holding nothing needs no session - this is the whole point of the header")
        .isNull();
  }

  @Test
  void theChainAndTheSuccessHandlerShareOneCache() {
    assertThat(context.getBean(RequestCache.class)).isSameAs(navigationRequestCache);
    assertThat(context.getBeansOfType(RequestCache.class))
        .as("a second cache is the bug this test exists for")
        .hasSize(1);
  }
}
