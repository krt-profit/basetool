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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.exchange.client.ExchangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.web.HomeLocationPreferenceReader;
import de.greluc.krt.profit.basetool.frontend.identity.web.ProfileController;
import de.greluc.krt.profit.basetool.frontend.inventory.web.SalePriceBasisPreferenceReader;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.SessionGateRefusalException;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGateFilter;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGates;
import de.greluc.krt.profit.basetool.frontend.kernel.web.GlobalExceptionHandler;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.web.MissionFrequencyWriteController;
import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationStatusDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.web.HomeLocationGate;
import de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A domain handler's own catch arm hands a session gate's refusal off instead of answering it
 * (REQ-SEC-028, REQ-PATCH-020, ADR-0385): an AJAX arm that builds its own JSON answers {@code 403}
 * with the gate's header, a form post's arm that redirects with a toast redirects to the gate's
 * page instead, and both discard the gate's cached pass. Every other backend failure is still
 * answered by the arm itself.
 */
class DomainArmSessionGateHandoffMvcTest {

  private static final UUID MISSION = UUID.fromString("5a1e0000-0000-4000-8000-00000000b001");

  private static final UUID FREQUENCY_TYPE =
      UUID.fromString("5a1e0000-0000-4000-8000-00000000b002");

  private final PatchChangeBackendClient patchChangeClient = mock(PatchChangeBackendClient.class);

  private final HomeLocationGate gate = new HomeLocationGate(patchChangeClient);

  private final SessionGates gates = new SessionGates(List.of(gate));

  private final MockHttpSession session = new MockHttpSession();

  private final IdentityBackendClient identityClient = mock(IdentityBackendClient.class);

  private final MissionBackendClient missionClient = mock(MissionBackendClient.class);

  /** The gate's refusal as the backend seam raises it. */
  private final SessionGateRefusalException refusal =
      new SessionGateRefusalException("held", null, 403, gate.problemCode(), null, null);

  /** A refusal carrying the gate's code without the gate's mark, so no gate's refusal. */
  private final BackendServiceException unmarked =
      new BackendServiceException("refused", null, 403, gate.problemCode(), null, List.of(), null);

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
  void theProfileDescriptionAjaxArmAnswersTheRefusalWithTheGatesHeaderAndDiscardsThePass()
      throws Exception {
    doThrow(refusal).when(identityClient).updateMyDescription(any());

    profile()
        .perform(descriptionAjax())
        .andExpect(status().isForbidden())
        .andExpect(header().string(gate.ajaxHeader(), gate.pagePath()));

    assertThat(gate.holds(requestInSession())).as("the pass was discarded").isTrue();
  }

  @Test
  void theProfileDescriptionAjaxArmStillAnswersAnyOtherFailureItself() throws Exception {
    doThrow(unmarked).when(identityClient).updateMyDescription(any());

    profile()
        .perform(descriptionAjax())
        .andExpect(status().isBadGateway())
        .andExpect(header().doesNotExist(gate.ajaxHeader()))
        .andExpect(jsonPath("$.detail").value("error.profile.update.failed"));

    assertThat(gate.holds(requestInSession())).as("the pass is kept").isFalse();
  }

  @Test
  void theFrequencyFormArmRedirectsTheRefusalToTheGatesPageAndDiscardsThePass() throws Exception {
    doThrow(refusal).when(missionClient).putFrequency(eq(MISSION), any());

    frequencies()
        .perform(frequencyForm())
        .andExpect(status().isFound())
        .andExpect(redirectedUrl(gate.pagePath()));

    assertThat(gate.holds(requestInSession())).as("the pass was discarded").isTrue();
  }

  @Test
  void theFrequencyFormArmStillAnswersAnyOtherFailureItself() throws Exception {
    doThrow(unmarked).when(missionClient).putFrequency(eq(MISSION), any());

    frequencies()
        .perform(frequencyForm())
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/missions/" + MISSION + "?error=error.mission.frequency.update"));

    assertThat(gate.holds(requestInSession())).as("the pass is kept").isFalse();
  }

  /**
   * Builds the profile page's AJAX save of the description, as its script posts it.
   *
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder descriptionAjax() {
    return post("/profile/description")
        .session(session)
        .header("X-Requested-With", "XMLHttpRequest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"description\":\"Pilot\",\"displayName\":\"Member\",\"version\":3}");
  }

  /**
   * Builds the mission page's form post of a frequency.
   *
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder frequencyForm() {
    return post("/missions/" + MISSION + "/frequencies")
        .session(session)
        .param("frequencyTypeId", FREQUENCY_TYPE.toString())
        .param("value", "123.45");
  }

  /**
   * Builds the profile controller behind the gate filter and the global error handling.
   *
   * @return the MockMvc
   */
  private @NotNull MockMvc profile() {
    return page(
        new ProfileController(
            identityClient,
            mock(NotificationBackendClient.class),
            mock(ExchangeBackendClient.class),
            mock(HomeLocationPreferenceReader.class),
            mock(SalePriceBasisPreferenceReader.class),
            messages()));
  }

  /**
   * Builds the mission frequency controller behind the gate filter and the global error handling.
   *
   * @return the MockMvc
   */
  private @NotNull MockMvc frequencies() {
    return page(new MissionFrequencyWriteController(missionClient));
  }

  /**
   * Builds a controller behind the gate filter and the global error handling.
   *
   * @param controller the controller under test
   * @return the MockMvc
   */
  private @NotNull MockMvc page(@NotNull Object controller) {
    MockEnvironment testProfile = new MockEnvironment();
    testProfile.setActiveProfiles("test");
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler(messages(), gates))
        .addFilters(new SessionGateFilter(gates, testProfile))
        .build();
  }

  /**
   * Builds a message source that answers every key with the key itself.
   *
   * @return the message source
   */
  private static @NotNull StaticMessageSource messages() {
    StaticMessageSource messages = new StaticMessageSource();
    messages.setUseCodeAsDefaultMessage(true);
    return messages;
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
