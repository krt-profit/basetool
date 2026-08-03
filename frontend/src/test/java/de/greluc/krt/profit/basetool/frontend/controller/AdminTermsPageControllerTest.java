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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Behaviour of the admin consent overview page (REQ-SEC-028).
 *
 * <p>Covers the two decisions that are easy to get wrong and invisible afterwards: the default
 * filter (the reason to open this page is "who is still missing", so {@code ALL} would bury the
 * rows that matter), and that a junk filter from the query string degrades to that default instead
 * of breaking the page or reaching the backend as-is.
 */
@SpringBootTest
class AdminTermsPageControllerTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** Opening the page without a filter asks the backend for the pending users. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void defaultsToThePendingFilter() throws Exception {
    mockMvc
        .perform(get("/admin/terms"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/terms"))
        .andExpect(model().attribute("termsFilter", "PENDING"));

    assertThat(requestedUri()).contains("filter=PENDING");
  }

  /**
   * The swap path returns the results FRAGMENT, not the whole document.
   *
   * <p>This is the interaction the page is for. {@code krtFetch.swap} appends {@code
   * fragment=results} and writes the response straight into {@code #admin-terms-results}; it bails
   * only on a redirect or a non-2xx, so a full page comes back 200 and gets nested inside its own
   * container — header, nav, heading and filter form and all — on every filter change and page
   * click, with nothing reporting the breakage. Shipped exactly that way and was caught in review.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void returnsTheResultsFragmentForASwap() throws Exception {
    mockMvc
        .perform(get("/admin/terms").param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/terms :: adminTermsResults"));
  }

  /**
   * Any other fragment value renders the whole page, so a stray parameter cannot blank the page.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheWholePageForAnUnknownFragmentValue() throws Exception {
    mockMvc
        .perform(get("/admin/terms").param("fragment", "something-else"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/terms"));
  }

  /** An explicit filter is honoured and echoed back so the select keeps its selection. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void honoursAnExplicitFilter() throws Exception {
    mockMvc
        .perform(get("/admin/terms").param("filter", "accepted"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("termsFilter", "ACCEPTED"));

    assertThat(requestedUri()).contains("filter=ACCEPTED");
  }

  /** A filter nobody offers degrades to the default rather than reaching the backend. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rejectsAnUnknownFilterByFallingBackToTheDefault() throws Exception {
    mockMvc
        .perform(get("/admin/terms").param("filter", "'; DROP TABLE app_user;--"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("termsFilter", "PENDING"));

    assertThat(requestedUri()).contains("filter=PENDING").doesNotContain("DROP");
  }

  /** A negative page index is clamped instead of being relayed. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void clampsANegativePageIndex() throws Exception {
    mockMvc.perform(get("/admin/terms").param("page", "-5")).andExpect(status().isOk());

    assertThat(requestedUri()).contains("page=0");
  }

  /**
   * A backend outage renders the page in its "could not be loaded" state rather than an error
   * screen, so an admin checking a rollout sees which half of the page is missing.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheFailureStateWhenTheBackendIsUnreachable() throws Exception {
    when(backendApiClient.get(anyString(), any(ParameterizedTypeReference.class)))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(get("/admin/terms"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/terms"))
        .andExpect(model().attribute("termsLoadFailed", true));
  }

  /** A non-admin has no business seeing who has and has not consented. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void refusesANonAdmin() throws Exception {
    mockMvc.perform(get("/admin/terms")).andExpect(status().isForbidden());
  }

  /**
   * Reads the overview URI the controller asked the backend for.
   *
   * @return the requested URI, query string included
   */
  private String requestedUri() {
    ArgumentCaptor<String> captor = ArgumentCaptor.captor();
    verify(backendApiClient).get(captor.capture(), any(ParameterizedTypeReference.class));
    return captor.getValue();
  }
}
