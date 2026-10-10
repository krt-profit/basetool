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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.identity.model.PersonSearchHitDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.PersonSearchResultDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.List;
import java.util.Locale;
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
 * Renders the person search on the list pattern (REQ-UI-027): page head with the admin eyebrow, the
 * search in a toolbar, hits as a row-link table, notices as alerts and the empty state.
 */
@SpringBootTest
class AdminPersonSearchPatternRenderTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders {@code /admin/person-search} in German with the given backend result.
   *
   * @param result the result the backend returns
   * @param query the query string, without {@code ?}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull PersonSearchResultDto result, @NotNull String query)
      throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(result);
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get("/admin/person-search?" + query).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** A hit with a record link becomes a row link; a hit without one stays plain text. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheHitsAsARowLinkTable() throws Exception {
    PersonSearchResultDto result =
        new PersonSearchResultDto(
            List.of(
                new PersonSearchHitDto(
                    "MEMBER", "app_user", "username", "abc-1", "Rufzeichen Alpha", "MEMBER"),
                new PersonSearchHitDto("BANK", "bank_tx", "memo", null, "Buchung Alpha", "BANK"),
                new PersonSearchHitDto("HANGAR", "ship", "custom_name", "x", "Schiff Alpha", null)),
            true,
            List.of("mission_participant.comment"));

    String html = render(result, "q=Alpha");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>System &amp; Daten<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
    assertThat(html.split("btn--cta", -1)).hasSize(2);
    assertThat(html)
        .contains("data-testid=\"person-search-term\"")
        .contains("class=\"toolbar__search\"")
        .contains("class=\"data-table data-table--stack\"")
        .containsPattern("class=\"row-link\"[^>]*href=\"/members/abc-1/edit\"")
        .containsPattern("class=\"row-link\"[^>]*href=\"/bank/manage\"")
        .contains(">Schiff Alpha<")
        .contains(">Mitglied<")
        .contains("data-testid=\"person-search-truncated\"")
        .contains("data-testid=\"person-search-capped\"")
        .containsPattern(
            "class=\"alert alert-warning\"[^>]*data-testid=\"person-search-truncated\"");
    assertThat(html.split("data-testid=\"person-search-row\"", -1)).hasSize(4);
    assertThat(html.split("class=\"row-link\"", -1)).hasSize(3);
  }

  /** A search without hits renders the empty state instead of a bare paragraph. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheEmptyState() throws Exception {
    String html = render(new PersonSearchResultDto(List.of(), false, List.of()), "q=Alpha");

    assertThat(html)
        .contains("data-testid=\"person-search-count\"")
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Treffer.")
        .doesNotContain("data-table--stack");
  }
}
