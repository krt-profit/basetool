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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionCategoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionTopicDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the evaluation matrix page on the work-page rules (REQ-UI-027): page head with the
 * promotion eyebrow and the matrix actions in the overflow menu, the toolbar with search and the
 * two boolean filters in the filter popover, the matrix in a flush card, and the empty states.
 */
@SpringBootTest
class PromotionManagePagePatternRenderTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Stubs one topic with one category and the given members, then renders {@code /promotion/manage}
   * in German.
   *
   * @param members the members the matrix lists
   * @param activeOrgUnitId the pinned org unit, or {@code null} for the all-squadrons view
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull List<UserDto> members, @Nullable UUID activeOrgUnitId)
      throws Exception {
    UUID topicId = UUID.randomUUID();
    PromotionTopicDto topic =
        new PromotionTopicDto(topicId, 0L, "Profit", null, 0, null, null, null);
    PromotionCategoryDto cat =
        new PromotionCategoryDto(
            UUID.randomUUID(), 0L, topicId, "Profit", "Trading", "desc", 0, null, null);
    when(backendApiClient.get(eq("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of(topic));
    when(backendApiClient.get(contains("/categories/by-topic/"), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of(cat));
    when(backendApiClient.get(
            contains("/api/v1/promotion/evaluations/all"), anyTypeRef(), any(Object[].class)))
        .thenReturn(new PageResponse<>(List.of(), 0, 1000, 0, 1, List.of()));
    when(backendApiClient.get(
            contains("/api/v1/promotion/evaluations/members"), anyTypeRef(), any(Object[].class)))
        .thenReturn(new PageResponse<>(members, 0, 1000, members.size(), 1, List.of()));
    when(backendApiClient.get(
            contains("/api/v1/promotion/eligibility/user/"), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    MockHttpServletRequestBuilder request = get("/promotion/manage").locale(Locale.GERMAN);
    if (activeOrgUnitId != null) {
      request = request.sessionAttr("iridium.activeOrgUnitId", activeOrgUnitId);
    }
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * A member the matrix can evaluate.
   *
   * @param username the member's username
   * @return the member
   */
  private static @NotNull UserDto member(@NotNull String username) {
    return new UserDto(
        UUID.randomUUID(),
        username,
        null,
        username,
        null,
        20,
        null,
        Set.of(),
        Set.of(),
        null,
        null,
        null,
        null,
        null,
        List.of(),
        0L,
        null,
        false);
  }

  /** The matrix page renders the page head, the toolbar and the matrix in a flush card. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersThePagePattern() throws Exception {
    String html = render(List.of(member("alice"), member("bob")), UUID.randomUUID());

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Beförderung<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>2<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("Stufen für alle Mitglieder verwalten");
    assertThat(html.split("btn--cta", -1)).hasSizeLessThanOrEqualTo(2);
    assertThat(html)
        .containsPattern("class=\"overflow-menu__item\"[^>]*data-trigger=\"pm-expand-all\"")
        .containsPattern("class=\"overflow-menu__item\"[^>]*data-trigger=\"pm-collapse-all\"")
        .containsPattern("class=\"overflow-menu__item\"[^>]*data-trigger=\"pm-export-csv\"")
        .doesNotContain("pm-toolbar");
    assertThat(html)
        .contains("class=\"toolbar\"")
        .containsPattern("id=\"pm-member-search\"[^>]*data-testid=\"toolbar-search\"")
        .contains("data-testid=\"pm-filter-toggle\"")
        .contains("id=\"pm-filter-panel\"")
        .containsPattern("id=\"pm-filter-eligible\"[^>]*data-trigger=\"pm-filter-change\"")
        .containsPattern("id=\"pm-filter-no-eval\"[^>]*data-trigger=\"pm-filter-change\"")
        .contains("data-filter-form=\"pm-filter-form\"")
        .contains("id=\"pm-bulk-panel\"");
    assertThat(html)
        .contains("class=\"card card--flush pm-matrix-wrapper\"")
        .contains("class=\"pm-matrix\"")
        .containsPattern("id=\"pm-empty-state\" hidden")
        .contains("Keine Mitglieder passen zum Filter.");
  }

  /** Without members the page shows the empty state instead of toolbar and matrix. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheEmptyStateWithoutMembers() throws Exception {
    String html = render(List.of(), UUID.randomUUID());

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Mitglieder gefunden.")
        .doesNotContain("class=\"pm-matrix\"")
        .doesNotContain("id=\"pm-member-search\"")
        .doesNotContain("data-testid=\"page-head-count\"")
        .doesNotContain("overflow-menu__item");
  }

  /** The all-squadrons view asks for a Staffel in an info alert and renders no matrix. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void asksForASquadronInTheAllSquadronsView() throws Exception {
    String html = render(List.of(member("alice")), null);

    assertThat(html)
        .containsPattern("class=\"alert alert-info\"[^>]*>Bitte wähle eine Staffel aus")
        .doesNotContain("class=\"pm-matrix\"")
        .doesNotContain("hud-box");
  }
}
