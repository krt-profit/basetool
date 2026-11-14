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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.TermsStatusDto;
import de.greluc.krt.profit.basetool.frontend.identity.web.TermsGate;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendErrorMapper;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGate;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGates;
import de.greluc.krt.profit.basetool.frontend.kernel.web.GlobalExceptionHandler;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationStatusDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.web.HomeLocationGate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * A session gate's refusal that meets a page section is handed off like any other gate refusal, for
 * the consent gate and the home-location gate alike (REQ-SEC-028, REQ-PATCH-020, ADR-0385): the
 * gate's cached verdict is discarded, a navigation is redirected to the gate's page and an AJAX
 * caller gets {@code 403} with the gate's header. Any other refusal still falls back.
 */
class SectionSessionGateHandoffMvcTest {

  private static final String PAGE = "/section-page";

  /**
   * The two gates in their order, each with its backend status read stubbed to let the member
   * through first and to hold them afterwards.
   *
   * @return one case per gate
   */
  static Stream<GateCase> gates() {
    IdentityBackendClient identity = mock(IdentityBackendClient.class);
    when(identity.termsStatus())
        .thenReturn(new TermsStatusDto(true, "v1"), new TermsStatusDto(false, "v2"));
    PatchChangeBackendClient patchChange = mock(PatchChangeBackendClient.class);
    when(patchChange.myHomeLocationStatus())
        .thenReturn(new HomeLocationStatusDto(false), new HomeLocationStatusDto(true));
    TermsGate terms = new TermsGate(identity);
    HomeLocationGate homeLocation = new HomeLocationGate(patchChange);
    SessionGates all = new SessionGates(List.of(terms, homeLocation));
    return Stream.of(
        new GateCase("consent", terms, all), new GateCase("home location", homeLocation, all));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("gates")
  void aSectionMeetingTheGatesRefusalRedirectsANavigationAndForgetsTheVerdict(GateCase gateCase)
      throws Exception {
    SessionGate gate = gateCase.gate();
    MockHttpSession session = new MockHttpSession();
    assertThat(gate.holds(requestIn(session)))
        .as("the cached verdict lets the member through")
        .isFalse();

    gateCase
        .page(refusedBy(gate))
        .perform(get(PAGE).session(session))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl(gate.pagePath()));

    assertThat(gate.holds(requestIn(session)))
        .as("the verdict was discarded, so the gate asks the backend again")
        .isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("gates")
  void aSectionMeetingTheGatesRefusalAnswersAnAjaxCallerWithTheGatesHeader(GateCase gateCase)
      throws Exception {
    SessionGate gate = gateCase.gate();

    gateCase
        .page(refusedBy(gate))
        .perform(get(PAGE).header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isForbidden())
        .andExpect(header().string(gate.ajaxHeader(), gate.pagePath()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("gates")
  void anyOtherRefusalStillFallsBackAndKeepsTheVerdict(GateCase gateCase) throws Exception {
    SessionGate gate = gateCase.gate();
    MockHttpSession session = new MockHttpSession();
    gate.holds(requestIn(session));

    gateCase
        .page(refusal("{\"code\":\"" + gate.problemCode() + "\"}"))
        .perform(get(PAGE).session(session))
        .andExpect(status().isOk())
        .andExpect(model().attribute("rows", List.of("fallback")));

    assertThat(gate.holds(requestIn(session))).as("the cached verdict is kept").isFalse();
  }

  /**
   * Builds the backend's refusal for a gate, marked as a session gate's.
   *
   * @param gate the gate
   * @return the refusal as the backend seam raises it
   */
  private static @NotNull BackendServiceException refusedBy(@NotNull SessionGate gate) {
    return refusal("{\"code\":\"" + gate.problemCode() + "\",\"sessionGate\":true}");
  }

  /**
   * Parses a backend {@code 403} the way the backend seam does.
   *
   * @param body the problem body
   * @return the parsed refusal
   */
  private static @NotNull BackendServiceException refusal(@NotNull String body) {
    return BackendErrorMapper.parseProblem(
        WebClientResponseException.create(
            403,
            "Forbidden",
            new HttpHeaders(),
            body.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8));
  }

  /**
   * Builds a request in a session, for asking a gate directly.
   *
   * @param session the session
   * @return the request
   */
  private static @NotNull MockHttpServletRequest requestIn(@NotNull MockHttpSession session) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", PAGE);
    request.setSession(session);
    return request;
  }

  /**
   * One session gate under test.
   *
   * @param name the case's display name
   * @param gate the gate
   * @param gates both gates in their order, as the frontend holds them
   */
  record GateCase(@NotNull String name, @NotNull SessionGate gate, @NotNull SessionGates gates) {

    /**
     * Builds the page behind the global error handling, its section failing with a refusal.
     *
     * @param failure the refusal the section's backend read raises
     * @return the page
     */
    @NotNull
    MockMvc page(@NotNull BackendServiceException failure) {
      StaticMessageSource messages = new StaticMessageSource();
      messages.setUseCodeAsDefaultMessage(true);
      return MockMvcBuilders.standaloneSetup(new SectionPage(failure))
          .setControllerAdvice(new GlobalExceptionHandler(messages, gates))
          .build();
    }

    @Override
    public @NotNull String toString() {
      return name;
    }
  }

  /** A page with one section read through {@link SectionFallback}. */
  @Controller
  @RequiredArgsConstructor
  @Slf4j
  static final class SectionPage {

    /** The refusal the section's backend read raises. */
    private final BackendServiceException failure;

    /**
     * Renders the page with the section's rows, or the fallback when the read fails.
     *
     * @param model receives {@code rows}
     * @return the page's view name
     */
    @GetMapping(PAGE)
    String page(Model model) {
      model.addAttribute(
          "rows",
          SectionFallback.load(
              log,
              "rows",
              () -> {
                throw failure;
              },
              List.of("fallback")));
      return "test/section";
    }
  }
}
