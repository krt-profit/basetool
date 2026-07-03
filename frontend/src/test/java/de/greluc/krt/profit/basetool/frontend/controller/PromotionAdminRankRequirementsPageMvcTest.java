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

import de.greluc.krt.profit.basetool.frontend.config.SquadronContextAdvice;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionCategoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionTopicDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RankRequirementDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
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
 * Renders {@code /promotion/admin/rank-requirements} as an OFFICER and asserts the toolbar create
 * button AND the inline event-handler registration survive end-to-end.
 *
 * <p>Regression guard for the silent template truncation that turned the "NEUE ANFORDERUNG" button
 * into a no-op. The page inlines a {@code Map<UUID, List<PromotionCategoryDto>>} into the bottom
 * {@code <script>} block via {@code [[${categoriesByTopic}]]}. The custom {@code
 * JavaTimeAwareJavaScriptSerializer} called {@code ObjectMapper.writeValue(writer, ...)} on
 * Thymeleaf's shared template writer; with Jackson's {@code AUTO_CLOSE_TARGET} default the writer
 * was closed immediately after the JSON was emitted, so every byte that followed (the message
 * bundle inlines, the {@code openCreateModal} helper, and the {@code window.krtEvents.on('click',
 * 'ar-open-create', ...)} wiring) was dropped. The page still rendered 200 OK but with an
 * unterminated {@code <script>} block, leaving the create button orphaned. The contained-string
 * assertions below pin both ends of that script so a future change that re-introduces a writer-
 * closing serializer is caught at the test layer instead of in production.
 */
@SpringBootTest
class PromotionAdminRankRequirementsPageMvcTest {

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
  @WithMockUser(roles = "OFFICER")
  void adminRankRequirements_rendersCreateButtonAndInlineScript_forOfficer() throws Exception {
    UUID topicId = UUID.randomUUID();
    UUID catId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();

    PromotionTopicDto topic = new PromotionTopicDto(topicId, 0L, "Profit", null, 0, null, null);
    PromotionCategoryDto cat =
        new PromotionCategoryDto(catId, 0L, topicId, "Profit", "Trading", null, 0, null, null);
    RankRequirementDto req =
        new RankRequirementDto(
            UUID.randomUUID(),
            0L,
            20,
            19,
            topicId,
            "Profit",
            catId,
            "Trading",
            "LEVEL_A",
            1,
            null,
            null,
            null);

    // SquadronContextAdvice fan-out: squadrons list + non-admin /me/active-org-unit lookup.
    PageResponse<SquadronDto> squadronPage =
        new PageResponse<>(
            List.of(new SquadronDto(squadronId, "IRIDIUM", "IRI", null, true, true, false, 0L)),
            0,
            1000,
            1,
            1,
            List.of());
    when(backendApiClient.get(contains("/api/v1/squadrons"), anyTypeRef()))
        .thenReturn(squadronPage);
    // availableSquadrons() reads the catalogue through the STATIC_DATA_CACHE (getCached) now
    // (REQ-DATA-007); stub that path too or the officer's promotion-feature flag resolves empty.
    when(backendApiClient.getCached(contains("/api/v1/squadrons"), anyTypeRef()))
        .thenReturn(squadronPage);
    when(backendApiClient.get(
            eq("/api/v1/me/active-org-unit"),
            eq(SquadronContextAdvice.ActiveOrgUnitResponse.class)))
        .thenReturn(new SquadronContextAdvice.ActiveOrgUnitResponse(squadronId));

    when(backendApiClient.get(contains("/api/v1/promotion/rank-requirements"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(req), 0, 1000, 1, 1, List.of()));
    when(backendApiClient.get(eq("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of(topic));
    when(backendApiClient.get(
            contains("/api/v1/promotion/categories/by-topic/" + topicId + "/all"), anyTypeRef()))
        .thenReturn(List.of(cat));
    when(backendApiClient.get(contains("/api/v1/promotion/categories?size="), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(cat), 0, 1000, 1, 1, List.of()));

    mockMvc
        .perform(get("/promotion/admin/rank-requirements"))
        .andExpect(status().isOk())
        // The create button must be present with its data-trigger so the inline JS wiring
        // can find it.
        .andExpect(content().string(containsString("data-trigger=\"ar-open-create\"")))
        // The inline JS that wires the click must be rendered with a real nonce attribute
        // and must contain the registration call.
        .andExpect(
            content().string(containsString("window.krtEvents.on('click', 'ar-open-create'")))
        // The Thymeleaf-inlined categories map must be valid JSON, not the literal placeholder.
        .andExpect(
            content()
                .string(containsString("var AR_CATEGORIES_BY_TOPIC = {\"" + topicId + "\":[")));
  }
}
