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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.hangar.client.HangarBackendClient;
import de.greluc.krt.profit.basetool.frontend.hangar.web.HangarPageController;
import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.web.HomeLocationPreferenceReader;
import de.greluc.krt.profit.basetool.frontend.identity.web.ProfileHomeLocationProxyController;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalogListLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.SessionGateRefusalException;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGateFilter;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGates;
import de.greluc.krt.profit.basetool.frontend.kernel.web.GlobalExceptionHandler;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationStatusDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.web.HomeLocationGate;
import de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The first backend refusal of a session gate, met while the frontend still holds the gate's cached
 * pass, is handed off through the shared AJAX relay and through a parallel page read's fallback as
 * it is through a page section (REQ-PATCH-020, REQ-SEC-028, ADR-0385): the pass is discarded, an
 * AJAX caller gets {@code 403} with the gate's header and a navigation is redirected to the page.
 */
class SharedFallbackSessionGateHandoffMvcTest {

  /** A choice of the profile's setting as the page's script posts it. */
  private static final String CHOICE =
      "{\"homeLocationId\":\"5a1e0000-0000-4000-8000-00000000a001\",\"version\":3}";

  private final PatchChangeBackendClient patchChangeClient = mock(PatchChangeBackendClient.class);

  private final HomeLocationGate gate = new HomeLocationGate(patchChangeClient);

  private final SessionGates gates = new SessionGates(List.of(gate));

  private final MockHttpSession session = new MockHttpSession();

  private final SessionGateRefusalException refusal =
      new SessionGateRefusalException("held", null, 403, gate.problemCode(), null, null);

  @BeforeEach
  void warmTheCachedPass() {
    when(patchChangeClient.myHomeLocationStatus())
        .thenReturn(new HomeLocationStatusDto(false), new HomeLocationStatusDto(true));
    assertThat(gate.holds(requestInSession()))
        .as("the cached pass lets the member through")
        .isFalse();
    ReauthPageMvc.signInMember("KRT_MEMBER");
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void theProfileRelayAnswersTheFirstRefusalWithTheGatesHeaderAndDiscardsThePass()
      throws Exception {
    IdentityBackendClient identityClient = mock(IdentityBackendClient.class);
    when(identityClient.updateMyHomeLocation(any())).thenThrow(refusal);

    page(new ProfileHomeLocationProxyController(identityClient))
        .perform(
            post("/profile/home-location")
                .session(session)
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CHOICE))
        .andExpect(status().isForbidden())
        .andExpect(header().string(gate.ajaxHeader(), gate.pagePath()));

    assertThat(gate.holds(requestInSession())).as("the pass was discarded").isTrue();
  }

  @Test
  void theHangarsParallelReadRedirectsTheFirstRefusalAndDiscardsThePass() throws Exception {
    HangarBackendClient hangarClient = mock(HangarBackendClient.class);
    when(hangarClient.myShips(anyInt(), anyInt(), any(), any(), any())).thenThrow(refusal);
    CachedCatalogListLoader catalogs = mock(CachedCatalogListLoader.class);
    when(catalogs.loadPageContent(any(), any(), anyString())).thenAnswer(_ -> new ArrayList<>());

    page(new HangarPageController(
            hangarClient,
            new ParallelPageLoader(),
            catalogs,
            mock(HomeLocationPreferenceReader.class)))
        .perform(get("/hangar").param("fragment", "results").session(session))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl(gate.pagePath()));

    assertThat(gate.holds(requestInSession())).as("the pass was discarded").isTrue();
  }

  /**
   * Builds a controller behind the gate filter and the global error handling.
   *
   * @param controller the controller under test
   * @return the MockMvc
   */
  private @NotNull MockMvc page(@NotNull Object controller) {
    StaticMessageSource messages = new StaticMessageSource();
    messages.setUseCodeAsDefaultMessage(true);
    MockEnvironment testProfile = new MockEnvironment();
    testProfile.setActiveProfiles("test");
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler(messages, gates))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .addFilters(new SessionGateFilter(gates, testProfile))
        .build();
  }

  /**
   * Builds a request in the test's session, for asking the gate directly.
   *
   * @return the request
   */
  private @NotNull MockHttpServletRequest requestInSession() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missions");
    request.setSession(session);
    return request;
  }
}
