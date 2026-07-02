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
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionCategoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionEligibilityDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionLevelContentDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionTopicDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RankRequirementDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * MVC-level rendering checks for the in-place AJAX fragments the promotion admin/manage pages swap
 * after a write instead of reloading (epic #571 / #580, spec REQ-FE-005).
 *
 * <p>Each {@code ?fragment=...} request must render <em>only</em> the section that changes — the
 * list of cards / the matrix body / a single eligibility cell — and must <em>not</em> carry the
 * surrounding page chrome (toolbars, modals, the second {@code &lt;table&gt;} header). Injecting a
 * whole page into the small results container would duplicate ids and double-render the toolbar, so
 * these boundary assertions are the regression guard for the fragment cut points.
 */
@SpringBootTest
class PromotionInPlaceFragmentMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void adminTopics_topicsResultsFragment_rendersCardsWithoutPageChromeOrModals() throws Exception {
    UUID topicId = UUID.randomUUID();
    UUID catId = UUID.randomUUID();
    PromotionTopicDto topic = new PromotionTopicDto(topicId, 0L, "Profit", null, 0, null, null);
    PromotionCategoryDto cat =
        new PromotionCategoryDto(catId, 0L, topicId, "Profit", "Trading", null, 0, null, null);
    PromotionLevelContentDto lc =
        new PromotionLevelContentDto(
            UUID.randomUUID(), 0L, catId, "Trading", "LEVEL_A", "x", null, null);

    when(backendApiClient.get(eq("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of(topic));
    when(backendApiClient.get(contains("/categories/by-topic/"), anyTypeRef()))
        .thenReturn(List.of(cat));
    when(backendApiClient.get(contains("/level-contents/by-category/"), anyTypeRef()))
        .thenReturn(List.of(lc));

    mockMvc
        .perform(
            get("/promotion/admin/topics")
                .param("fragment", "topicsResults")
                .sessionAttr("iridium.activeOrgUnitId", UUID.randomUUID()))
        .andExpect(status().isOk())
        // The fragment must contain the topic card (proves the list rendered) ...
        .andExpect(content().string(containsString("data-pa-topic-id=\"" + topicId + "\"")))
        // ... but NOT the page-level toolbar trigger or the create/edit modals that live
        // outside the swapped region (a full-page leak would duplicate them).
        .andExpect(content().string(not(containsString("pa-open-create-topic"))))
        .andExpect(content().string(not(containsString("id=\"modal-create-topic\""))))
        .andExpect(content().string(not(containsString("<title"))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void adminRankRequirements_ranksResultsFragment_rendersRowsWithoutToolbarOrModals()
      throws Exception {
    UUID catId = UUID.randomUUID();
    RankRequirementDto req =
        new RankRequirementDto(
            UUID.randomUUID(), 0L, 20, 19, null, null, catId, null, "LEVEL_A", 1, null, null, null);

    when(backendApiClient.get(contains("/api/v1/promotion/rank-requirements"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(req), 0, 1000, 1, 1, List.of()));
    when(backendApiClient.get(eq("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(contains("/api/v1/promotion/categories?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of()));

    mockMvc
        .perform(
            get("/promotion/admin/rank-requirements")
                .param("fragment", "ranksResults")
                .sessionAttr("iridium.activeOrgUnitId", UUID.randomUUID()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-ar-req-id")))
        .andExpect(content().string(not(containsString("ar-open-create"))))
        .andExpect(content().string(not(containsString("id=\"modal-create\""))))
        .andExpect(content().string(not(containsString("<title"))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void manage_matrixBodyFragment_rendersTableWithoutToolbarOrBulkPanel() throws Exception {
    UUID topicId = UUID.randomUUID();
    UUID catId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    PromotionTopicDto topic = new PromotionTopicDto(topicId, 0L, "Profit", null, 0, null, null);
    PromotionCategoryDto cat =
        new PromotionCategoryDto(catId, 0L, topicId, "Profit", "Trading", null, 0, null, null);
    UserDto member =
        new UserDto(
            memberId,
            "alice",
            null,
            "alice",
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
            java.util.List.of(),
            0L,
            null,
            false);
    PromotionEligibilityDto elig =
        new PromotionEligibilityDto(memberId.toString(), 20, 19, true, true, List.of());

    when(backendApiClient.get(eq("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of(topic));
    when(backendApiClient.get(contains("/categories/by-topic/"), anyTypeRef()))
        .thenReturn(List.of(cat));
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/all"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 10000, 0, 0, List.of()));
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/members"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(member), 0, 1000, 1, 1, List.of()));
    when(backendApiClient.get(
            contains("/api/v1/promotion/eligibility/user/" + memberId), anyTypeRef()))
        .thenReturn(List.of(elig));

    mockMvc
        .perform(
            get("/promotion/manage")
                .param("fragment", "matrixBody")
                .sessionAttr("iridium.activeOrgUnitId", UUID.randomUUID()))
        .andExpect(status().isOk())
        // The matrix and its member rows must render ...
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("pm-matrix"),
                        containsString("data-pm-user-id=\"" + memberId + "\""))))
        // ... while the toolbar search box and bulk-edit panel that sit outside the swapped
        // region must stay out of the fragment.
        .andExpect(content().string(not(containsString("id=\"pm-member-search\""))))
        .andExpect(content().string(not(containsString("id=\"pm-bulk-panel\""))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void manage_eligibilityCellFragment_rendersOnlyChipsForOneMember() throws Exception {
    UUID memberId = UUID.randomUUID();
    PromotionEligibilityDto elig =
        new PromotionEligibilityDto(memberId.toString(), 20, 19, true, true, List.of());

    // The eligibilityCell branch returns early after a single per-member eligibility fetch —
    // it must not trigger the full matrix build (members / evaluations / topics).
    when(backendApiClient.get(
            contains("/api/v1/promotion/eligibility/user/" + memberId), anyTypeRef()))
        .thenReturn(List.of(elig));

    mockMvc
        .perform(
            get("/promotion/manage")
                .param("fragment", "eligibilityCell")
                .param("userId", memberId.toString())
                .sessionAttr("iridium.activeOrgUnitId", UUID.randomUUID()))
        .andExpect(status().isOk())
        // An eligible configured rule renders an eligibility chip ...
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("eligibility-chip"),
                        containsString("data-pm-eligible=\"true\""))))
        // ... but the fragment is just the cell content: no surrounding table or member row.
        .andExpect(content().string(not(containsString("pm-matrix"))))
        .andExpect(content().string(not(containsString("data-pm-user-id"))));
  }
}
