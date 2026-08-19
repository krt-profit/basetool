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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.TermsClauseDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.TermsDocumentDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.TermsSectionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.TermsStatusDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Behaviour of the Terms-of-Use consent gate page (REQ-SEC-028).
 *
 * <p>Two of these are about not locking people out. A backend hiccup on the status read must still
 * render the page — the gate's job is to obtain consent, and failing closed there would mean nobody
 * can get through until the backend recovers. Conversely, a failure to <em>record</em> consent must
 * report an error rather than a silent success, because a user waved through without a stored row
 * is asked again on the next request and never understands why.
 */
@SpringBootTest
class TermsAcceptancePageControllerTest {

  private static final String STATUS_URI = "/api/v1/terms/status";
  private static final String ACCEPTANCE_URI = "/api/v1/terms/acceptance";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  /** Backend endpoint serving the wording the gate asks about (ADR-0138). */
  private static final String DOCUMENT_URI = "/api/v1/terms/document";

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // Stubbed for every test rather than per case: the gate cannot render without the wording,
    // so leaving it out would fail each test for a reason unrelated to what it asserts.
    when(backendApiClient.get(eq(DOCUMENT_URI), eq(TermsDocumentDto.class))).thenReturn(document());
  }

  /**
   * The wording the backend serves, trimmed to what the gate has to prove it renders.
   *
   * @return a one-section document with a bulleted clause
   */
  private static TermsDocumentDto document() {
    return new TermsDocumentDto(
        "v1",
        "Nutzungsbedingungen",
        "Diese Nutzungsbedingungen regeln die Nutzung des Profit Basetool.",
        List.of(
            new TermsSectionDto(
                "4. Pflichten der Nutzer",
                List.of(
                    new TermsClauseDto(
                        "Der Nutzer verpflichtet sich zu Folgendem:",
                        List.of("Keine technischen Eingriffe."))))),
        "Stand dieser Nutzungsbedingungen: 05.08.2026");
  }

  /**
   * The gate shows the wording it is asking about.
   *
   * <p>The point of the whole move: a gate that rendered from its own copy could ask for consent to
   * text other than the one the acceptance is recorded against, and no other test would notice.
   */
  @Test
  @WithMockUser
  void rendersTheWordingServedByTheBackend() throws Exception {
    when(backendApiClient.get(eq(STATUS_URI), eq(TermsStatusDto.class)))
        .thenReturn(new TermsStatusDto(false, "v1"));

    mockMvc
        .perform(get("/terms/accept"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("4. Pflichten der Nutzer")))
        .andExpect(content().string(containsString("Keine technischen Eingriffe.")));
  }

  /**
   * A gate whose wording cannot be read is an error, not an emptier gate.
   *
   * <p>Deliberately the opposite tolerance from the status read above: a stale "not accepted" costs
   * one extra click, but consent to a text that was never displayed is not consent at all.
   */
  @Test
  @WithMockUser
  void failsWhenTheWordingCannotBeRead() throws Exception {
    when(backendApiClient.get(eq(STATUS_URI), eq(TermsStatusDto.class)))
        .thenReturn(new TermsStatusDto(false, "v1"));
    when(backendApiClient.get(eq(DOCUMENT_URI), eq(TermsDocumentDto.class)))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(get("/terms/accept"))
        // The error page, not the gate. Asserted on the resolved view rather than on the
        // status: the advice renders error/error and MockMvc reports the pre-dispatch 200,
        // so a status assertion here would pass for a gate that rendered perfectly fine.
        .andExpect(view().name("error/error"))
        .andExpect(content().string(not(containsString("4. Pflichten der Nutzer"))));
  }

  /** A user who has not consented sees the gate. */
  @Test
  @WithMockUser
  void rendersTheGateForAUserWhoHasNotConsented() throws Exception {
    when(backendApiClient.get(eq(STATUS_URI), eq(TermsStatusDto.class)))
        .thenReturn(new TermsStatusDto(false, "v1"));

    mockMvc
        .perform(get("/terms/accept"))
        .andExpect(status().isOk())
        .andExpect(view().name("terms-accept"));
  }

  /**
   * A user who already consented is sent into the tool rather than asked again — the second-tab
   * case, where the gate would otherwise look broken.
   */
  @Test
  @WithMockUser
  void redirectsAUserWhoAlreadyConsented() throws Exception {
    when(backendApiClient.get(eq(STATUS_URI), eq(TermsStatusDto.class)))
        .thenReturn(new TermsStatusDto(true, "v1"));

    mockMvc
        .perform(get("/terms/accept"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/"));
  }

  /** An unreadable status still renders the gate; failing closed here would block everyone. */
  @Test
  @WithMockUser
  void rendersTheGateWhenTheStatusCannotBeRead() throws Exception {
    when(backendApiClient.get(eq(STATUS_URI), eq(TermsStatusDto.class)))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(get("/terms/accept"))
        .andExpect(status().isOk())
        .andExpect(view().name("terms-accept"));
  }

  /** Accepting relays to the backend and reports success to the page's AJAX write. */
  @Test
  @WithMockUser
  void recordsConsentAndAnswersNoContent() throws Exception {
    mockMvc.perform(post("/terms/accept").with(csrf())).andExpect(status().isNoContent());

    verify(backendApiClient).post(eq(ACCEPTANCE_URI), any(), eq(Void.class));
  }

  /**
   * A backend that cannot record consent answers 502, so the page shows its retry message instead
   * of navigating the user into a tool that will bounce them straight back to this gate.
   */
  @Test
  @WithMockUser
  void reportsABadGatewayWhenConsentCannotBeRecorded() throws Exception {
    when(backendApiClient.post(eq(ACCEPTANCE_URI), any(), eq(Void.class)))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc.perform(post("/terms/accept").with(csrf())).andExpect(status().isBadGateway());
  }
}
