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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.TermsAcceptanceStatusDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
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
 * Renders the admin consent overview on the list pattern (REQ-UI-027): page head with the admin
 * eyebrow and count, the filter as a segmented control carrying the pending count, the stacked
 * table with a translated status, and the empty state.
 */
@SpringBootTest
class AdminTermsPatternRenderTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders {@code /admin/terms} in German with the given rows and a pending count of three.
   *
   * @param rows the rows the backend returns
   * @param query the query string, without {@code ?}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(
      @NotNull PageResponse<TermsAcceptanceStatusDto> rows, @NotNull String query)
      throws Exception {
    when(backendApiClient.get(startsWith("/api/v1/admin/terms?"), anyTypeRef())).thenReturn(rows);
    when(backendApiClient.get(eq("/api/v1/admin/terms/pending-count"), anyClass()))
        .thenReturn(new AdminTermsPageController.PendingCountView(3, "v1"));
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get("/admin/terms?" + query).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** The overview renders on the pattern with the accepted segment selected from the URL. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheListPattern() throws Exception {
    TermsAcceptanceStatusDto row =
        new TermsAcceptanceStatusDto(
            UUID.randomUUID(), "alpha", "Alpha", Instant.parse("2026-09-01T10:00:00Z"));
    String html =
        render(new PageResponse<>(List.of(row), 0, 25, 1L, 1, List.of()), "filter=ACCEPTED");
    String filterForm = html.substring(html.indexOf("id=\"admin-terms-filter-form\""));
    filterForm = filterForm.substring(0, filterForm.indexOf("</form>"));

    assertThat(filterForm).doesNotContain("type=\"submit\"").doesNotContain("<select");
    assertThat(html)
        .containsPattern("class=\"page-eyebrow\"[^>]*>Benutzer &amp; Inhalte<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .contains("data-list-count-for=\"admin-terms-results\"")
        .contains("data-testid=\"segment-filter-pending\"")
        .containsPattern("name=\"filter\" value=\"ACCEPTED\" checked=\"checked\"")
        .containsPattern("class=\"seg-count\">3<")
        .contains("class=\"data-table data-table--stack\"")
        .containsPattern("class=\"chip chip--success\">Zugestimmt<")
        .contains("01.09.2026 10:00 UTC")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("btn--cta");
  }

  /** An empty selection renders the empty state and no table. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheEmptyState() throws Exception {
    String html =
        render(
            new PageResponse<>(List.<TermsAcceptanceStatusDto>of(), 0, 25, 0L, 0, List.of()), "");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Einträge für diese Auswahl.")
        .doesNotContain("data-table--stack")
        .containsPattern("name=\"filter\" value=\"PENDING\" checked=\"checked\"");
  }
}
