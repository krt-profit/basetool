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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the profile page's deletion card fragment in each of its states: every notice is a
 * warning alert, never a HUD box with one-off classes (REQ-UI-027).
 */
@SpringBootTest
@WithMockUser(roles = "KRT_MEMBER")
class ProfileDeletionCardRenderMvcTest {

  private static final String URI = "/api/v1/users/me/deletion-request";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Renders the card fragment.
   *
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render() throws Exception {
    return mockMvc
        .perform(get("/profile/deletion-request").param("fragment", "card"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * Builds a deletion request in the given status.
   *
   * @param status the request status
   * @return the request as the backend returns it
   */
  private static @NotNull Map<String, Object> request(@NotNull String status) {
    Map<String, Object> request = new HashMap<>();
    request.put("status", status);
    request.put("eraseHistoryRequested", false);
    request.put("decisionNote", "Offene Buchungen");
    return request;
  }

  /** A pending request shows the warning alert and the withdraw action as a quiet button. */
  @Test
  void aPendingRequestShowsAWarningAlert() throws Exception {
    when(backendApiClient.get(eq(URI), anyTypeRef())).thenReturn(request("PENDING"));

    String html = render();

    assertThat(html)
        .containsPattern(
            "class=\"alert alert-warning"
                + " profile-deletion-warning\"[^>]*data-testid=\"profile-deletion-pending\"")
        .contains("class=\"btn btn-ghost\" id=\"profile-deletion-withdraw\"")
        .doesNotContain("hud-box")
        .doesNotContain("krtm-");
  }

  /** A declined request shows the decision note in the same alert. */
  @Test
  void aDeclinedRequestShowsTheNoteInAWarningAlert() throws Exception {
    when(backendApiClient.get(eq(URI), anyTypeRef())).thenReturn(request("DECLINED"));

    String html = render();

    assertThat(html)
        .containsPattern(
            "class=\"alert alert-warning"
                + " profile-deletion-warning\"[^>]*data-testid=\"profile-deletion-declined\"")
        .contains("Offene Buchungen")
        .doesNotContain("hud-box")
        .doesNotContain("krtm-");
  }

  /** A backend outage says so in the alert instead of offering a new request. */
  @Test
  void anOutageShowsTheUnavailableAlert() throws Exception {
    when(backendApiClient.get(eq(URI), anyTypeRef()))
        .thenThrow(new BackendServiceException("down", null, 503));

    String html = render();

    assertThat(html)
        .containsPattern(
            "class=\"alert alert-warning"
                + " profile-deletion-warning\"[^>]*data-testid=\"profile-deletion-unavailable\"")
        .doesNotContain("data-testid=\"profile-deletion-open\"")
        .doesNotContain("hud-box");
  }
}
