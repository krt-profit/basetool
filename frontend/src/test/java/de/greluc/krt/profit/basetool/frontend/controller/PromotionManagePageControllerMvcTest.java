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
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.MemberEvaluationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionCategoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionEligibilityDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionTopicDto;
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
 * MVC-level rendering checks for {@code /promotion/manage}.
 *
 * <p>Originally added because the category column headers in the matrix's second header row went
 * missing entirely — the rendered HTML had the topic group cell with the correct {@code colspan}
 * (e.g. {@code colspan="2"}) but the row immediately below it was empty where the two category
 * names should have been. The cause was a Thymeleaf 3.x quirk: a {@code <th:block th:each="topic"
 * th:with="topicId=...">} wrapper followed by {@code <th th:each="cat :
 * ${categoriesByTopic[topicId]}">} inside it evaluates the inner expression to zero iterations,
 * even though the same map access worked fine on the outer {@code colspan} attribute (using {@code
 * topic.id.toString()} inline). The fix iterates the flat {@code ${categories}} list instead, which
 * the body rows already use, so the column header now lines up with the column body cell-for-cell.
 *
 * <p>The same pattern broke the bulk-edit dropdown's {@code <optgroup>}: the topic label rendered
 * but the {@code <option>} elements inside it never did, leaving the officer with an empty
 * dropdown. There the fix is the explicit {@code categoriesByTopic.get(topic.id.toString())} form,
 * which sidesteps the gotcha while preserving the topic→category grouping the dropdown needs.
 */
@SpringBootTest
class PromotionManagePageControllerMvcTest {

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
  void manage_rendersCategoryColumnHeaders_andBulkEditOptions() throws Exception {
    UUID topicId = UUID.randomUUID();
    UUID catId1 = UUID.randomUUID();
    UUID catId2 = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();

    PromotionTopicDto topic = new PromotionTopicDto(topicId, 0L, "Profit", null, 0, null, null);
    PromotionCategoryDto cat1 =
        new PromotionCategoryDto(
            catId1, 0L, topicId, "Profit", "Trading", "Some description", 0, null, null);
    PromotionCategoryDto cat2 =
        new PromotionCategoryDto(
            catId2, 0L, topicId, "Profit", "Mining", "Mining description", 1, null, null);
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
    MemberEvaluationDto eval =
        new MemberEvaluationDto(
            UUID.randomUUID(),
            0L,
            memberId.toString(),
            catId1,
            "Trading",
            null,
            "Profit",
            "LEVEL_A",
            null,
            null);
    PromotionEligibilityDto elig =
        new PromotionEligibilityDto(memberId.toString(), 20, 19, true, true, List.of());

    when(backendApiClient.get(eq("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of(topic));
    when(backendApiClient.get(
            contains("/api/v1/promotion/categories/by-topic/" + topicId + "/all"), anyTypeRef()))
        .thenReturn(List.of(cat1, cat2));
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/all"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(eval), 0, 10000, 1, 1, List.of()));
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/members"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(member), 0, 1000, 1, 1, List.of()));
    when(backendApiClient.get(
            contains("/api/v1/promotion/eligibility/user/" + memberId), anyTypeRef()))
        .thenReturn(List.of(elig));

    // Pin a squadron: the management matrix only renders for a single active squadron context.
    // Without a pin an admin is in all-squadrons mode and the page shows a "pick a squadron"
    // prompt instead of the cross-staffel merge, so the column headers under test are hidden.
    mockMvc
        .perform(get("/promotion/manage").sessionAttr("iridium.activeOrgUnitId", UUID.randomUUID()))
        .andExpect(status().isOk())
        // The category-name <th> for each column must carry both the category name as text and
        // the data-pm-category-name attribute the CSV exporter reads. Without the fix neither
        // appears in the rendered HTML — the entire category row collapses to whitespace.
        .andExpect(
            content().string(containsString("data-pm-category-name=\"Trading\">Trading</th>")))
        .andExpect(content().string(containsString("data-pm-category-name=\"Mining\">Mining</th>")))
        // The bulk-edit dropdown's <optgroup> must contain real <option> elements for every
        // category. The pre-fix output had `<optgroup label="Profit">` followed by an immediate
        // `</optgroup>` — the officer could pick a topic label but never a category to apply.
        .andExpect(content().string(containsString("<optgroup label=\"Profit\">")))
        .andExpect(
            content().string(containsString("<option value=\"" + catId1 + "\">Trading</option>")))
        .andExpect(
            content().string(containsString("<option value=\"" + catId2 + "\">Mining</option>")));
  }
}
