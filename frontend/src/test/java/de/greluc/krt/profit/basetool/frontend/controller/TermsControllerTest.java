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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.TermsClauseDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.TermsDocumentDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.TermsSectionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@org.springframework.security.test.context.support.WithMockUser
class TermsControllerTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.getTermsDocumentAnonymously()).thenReturn(document());
  }

  /**
   * Returns a minimal two-section terms document, one paragraph with bullets and one without.
   *
   * @return a two-section document
   */
  private static TermsDocumentDto document() {
    return new TermsDocumentDto(
        "test-version",
        "Nutzungsbedingungen",
        "Diese Nutzungsbedingungen regeln die Nutzung des Profit Basetool.",
        List.of(
            new TermsSectionDto(
                "1. Geltungsbereich",
                List.of(
                    new TermsClauseDto("Sie gelten zwischen Betreiber und Nutzer.", List.of()))),
            new TermsSectionDto(
                "4. Pflichten der Nutzer",
                List.of(
                    new TermsClauseDto(
                        "Der Nutzer verpflichtet sich zu Folgendem:",
                        List.of(
                            "Wahrheitsgem\u00e4\u00dfe Angaben.",
                            "Keine technischen Eingriffe."))))),
        "Stand dieser Nutzungsbedingungen: 05.08.2026");
  }

  @Test
  void shouldReturnTermsView() throws Exception {
    mockMvc.perform(get("/terms")).andExpect(status().isOk()).andExpect(view().name("terms"));
  }

  /**
   * A backend failure renders the notice, not the error view.
   *
   * @throws Exception when the request could not be performed
   */
  @Test
  void aBackendOutageRendersTheNoticeRatherThanTheErrorView() throws Exception {
    when(backendApiClient.getTermsDocumentAnonymously())
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(get("/terms"))
        .andExpect(status().isOk())
        .andExpect(view().name("terms"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(
                            "Sie gelten zwischen Betreiber und Nutzer."))));
  }

  /** The page renders the headings, clauses and bullets served by the backend, not a local copy. */
  @Test
  void rendersTheDocumentServedByTheBackend() throws Exception {
    mockMvc
        .perform(get("/terms"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("1. Geltungsbereich")))
        .andExpect(content().string(containsString("Der Nutzer verpflichtet sich zu Folgendem:")))
        .andExpect(content().string(containsString("Keine technischen Eingriffe.")))
        .andExpect(
            content().string(containsString("Stand dieser Nutzungsbedingungen: 05.08.2026")));
  }

  /**
   * A paragraph without bullets emits no list at all.
   *
   * <p>An empty {@code <ul>} is invisible on screen and announced by a screen reader as a list of
   * nothing, so its absence is worth pinning rather than leaving to the template's shape.
   */
  @Test
  void rendersNoEmptyListForAClauseWithoutBullets() throws Exception {
    String html = mockMvc.perform(get("/terms")).andReturn().getResponse().getContentAsString();

    assertThat(html)
        .doesNotContain("<ul class=\"krtm-list-style-type-disc-margin-left-2rem-a571\"></ul>");
  }
}
