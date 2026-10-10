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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the member list on the list pattern (REQ-UI-027): page head with the admin eyebrow, count
 * and the sync action in the overflow menu, the search-only toolbar, the row-link table with the
 * translated Keycloak status, the list foot, and the empty state.
 */
@SpringBootTest
class MembersListPatternRenderTest {

  private static final UUID PRESENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");

  private static final UUID MISSING_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders {@code /members} in German as an admin with the given page from the backend.
   *
   * @param page the page the backend returns
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull PageResponse<UserDto> page) throws Exception {
    when(backendApiClient.get(eq("/api/v1/users?sort=username,asc"), anyTypeRef()))
        .thenReturn(page);
    when(backendApiClient.get(
            eq("/api/v1/org-units/members/{id}/memberships"), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(
            get("/members")
                .locale(Locale.GERMAN)
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * A member row as the user listing returns it.
   *
   * @param id the member id
   * @param name the visible name
   * @param inKeycloak whether the account still exists in Keycloak
   * @return the row
   */
  private static @NotNull UserDto member(
      @NotNull UUID id, @NotNull String name, boolean inKeycloak) {
    return new UserDto(
        id,
        name,
        name,
        name,
        null,
        5,
        null,
        Set.of("ROLE_KRT_MEMBER"),
        Set.of(),
        null,
        false,
        false,
        inKeycloak,
        null,
        List.of(),
        1L,
        null,
        Boolean.FALSE);
  }

  /** The list renders on the pattern: head, toolbar, row link, translated status, foot. */
  @Test
  void rendersTheListPattern() throws Exception {
    String html =
        render(
            new PageResponse<>(
                List.of(
                    member(PRESENT_ID, "AlicePresent", true), member(MISSING_ID, "BobGone", false)),
                0,
                20,
                2L,
                1,
                List.of("username,asc")));

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Benutzer &amp; Inhalte<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>2<")
        .contains("data-list-count-for=\"members-results\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("Verwalte die Mitglieder");
    assertThat(html.split("btn--cta", -1)).hasSizeLessThanOrEqualTo(2);
    assertThat(html)
        .containsPattern("class=\"overflow-menu__item\"[^>]*data-trigger=\"members-sync\"")
        .contains("data-testid=\"toolbar-search\"")
        .containsPattern("id=\"member-search\" name=\"search\"")
        .doesNotContain("members-filter-toggle")
        .doesNotContain("members-filter-panel");
    assertThat(html)
        .contains("class=\"data-table data-table--stack\"")
        .contains("data-testid=\"member-row\"")
        .containsPattern(
            "class=\"row-link\"[^>]*href=\"/members/" + PRESENT_ID + "/edit\\?source=members\"")
        .containsPattern("class=\"status-pill status-active\">In Keycloak<")
        .containsPattern("class=\"status-pill status-rejected\">Nicht in Keycloak<")
        .doesNotContain(">true<")
        .doesNotContain(">false<")
        .contains("data-trigger=\"members-delete-user\"")
        .contains("class=\"row-chevron\"")
        .contains("data-list-total=\"2\"")
        .contains("1–2 von 2");
  }

  /** An empty result renders the empty state and no table. */
  @Test
  void rendersTheEmptyState() throws Exception {
    String html = render(new PageResponse<>(List.<UserDto>of(), 0, 20, 0L, 0, List.of()));

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Mitglieder gefunden.")
        .doesNotContain("data-table--stack")
        .doesNotContain("data-list-total");
  }
}
