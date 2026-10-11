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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.identity.model.AdminDeletionRequestDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
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
 * Renders the deletion-request queue on the list pattern (REQ-UI-027): page head with the system
 * eyebrow and count, the deadline as an alert, the stacked table with extra-small row actions, and
 * the empty state instead of a colspan row.
 */
@SpringBootTest
class AdminDeletionRequestsListPatternRenderTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders the queue in German with the given requests from the backend.
   *
   * @param requests the queue the backend returns
   * @param query the query string, without {@code ?}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(
      @NotNull List<AdminDeletionRequestDto> requests, @NotNull String query) throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(requests);
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get("/admin/deletion-requests?" + query).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * A pending request as the queue lists it.
   *
   * @return the request
   */
  private static @NotNull AdminDeletionRequestDto request() {
    return new AdminDeletionRequestDto(
        UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
        UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
        "pilot_one",
        "PENDING",
        true,
        Instant.parse("2026-09-20T08:00:00Z"),
        null,
        null,
        3L);
  }

  /**
   * The queue renders on the pattern with its row ids and actions intact.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheListPattern() throws Exception {
    String html = render(List.of(request()), "");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>System &amp; Daten<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .contains("data-list-count-for=\"deletionRequestsHost\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("btn--cta")
        .contains("class=\"alert alert-warning dr-note\"")
        .contains("id=\"deletionRequestsHost\" class=\"card card--flush\"")
        .contains("class=\"data-table data-table--stack\"")
        .contains("id=\"dr-row-00000000-0000-0000-0000-0000000000aa\"")
        .contains("class=\"btn btn-quiet-danger btn-xs\" data-action=\"execute\"")
        .contains("class=\"btn btn-ghost btn-xs\" data-action=\"decline\"")
        .contains("krt-modal--danger")
        .doesNotContain("colspan");
  }

  /**
   * An empty queue renders the empty state, keeping the id the page has always had.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheEmptyState() throws Exception {
    String html = render(List.of(), "fragment=rows");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("id=\"deletionRequestsEmpty\"")
        .contains("Keine offenen Löschanträge.")
        .doesNotContain("data-table--stack")
        .doesNotContain("colspan");
  }
}
