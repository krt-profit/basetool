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

package de.greluc.krt.profit.basetool.frontend.promotion.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.promotion.model.MemberEvaluationDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionCategoryDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionEligibilityDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionLevelContentDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionRequirementCheckDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionTopicDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.RankRequirementDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * Renders the promotion area pages on their redesigned patterns (REQ-UI-027): the topic editor as
 * master-detail, the rank requirements as a rank-step × topic matrix with a rule list, the overview
 * as numbered steps and "Meine Bewertungen" with the progress block above one list.
 */
@SpringBootTest
class PromotionAreaPagePatternRenderTest {

  private final UUID topicA = UUID.randomUUID();
  private final UUID topicB = UUID.randomUUID();
  private final UUID categoryB = UUID.randomUUID();

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  /** Builds the MockMvc and stubs two topics, one category under the second and the caller. */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.get(eq("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of(topic(topicA, "Alpha", 0), topic(topicB, "Bravo", 1)));
    when(backendApiClient.get(
            contains("/categories/by-topic/{topicId}"), anyTypeRef(), eq(topicA.toString())))
        .thenReturn(List.of());
    when(backendApiClient.get(
            contains("/categories/by-topic/{topicId}"), anyTypeRef(), eq(topicB.toString())))
        .thenReturn(
            List.of(
                new PromotionCategoryDto(
                    categoryB, 3L, topicB, "Bravo", "Funk", "Disziplin", 0, null, null)));
    when(backendApiClient.get(
            contains("/level-contents/by-category/"), anyTypeRef(), any(Object[].class)))
        .thenReturn(
            List.of(
                new PromotionLevelContentDto(
                    UUID.randomUUID(), 1L, categoryB, "Funk", "LEVEL_B", "Sauber", null, null)));
    when(backendApiClient.get(contains("/api/v1/promotion/categories?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of()));
    when(backendApiClient.get(eq("/api/v1/users/me"), anyTypeRef())).thenReturn(member(20));
  }

  /**
   * Renders a promotion page in German with a squadron pinned.
   *
   * @param path the page path including its query
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull String path) throws Exception {
    return mockMvc
        .perform(
            get(path)
                .locale(Locale.GERMAN)
                .sessionAttr("iridium.activeOrgUnitId", UUID.randomUUID()))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** The topic editor is a master-detail with one primary action and the requested topic open. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void topicsRenderAsMasterDetail() throws Exception {
    String html = render("/promotion/admin/topics?topic=" + topicB);
    String main = html.substring(html.indexOf("<main"), html.indexOf("id=\"pa-save-all-banner\""));

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Beförderung<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>2<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("<details")
        .doesNotContain("pa-expand-all-topics");
    assertThat(main.split("btn--cta", -1)).hasSize(2);
    assertThat(main)
        .containsPattern("data-trigger=\"pa-open-create-topic\" data-testid=\"page-head-primary\"")
        .contains("class=\"master-detail pa-md\"")
        .containsPattern("id=\"pa-topic-pane-" + topicA + "\"[^>]*hidden")
        .doesNotContainPattern("id=\"pa-topic-pane-" + topicB + "\"[^>]*hidden")
        .contains("data-pa-category-id=\"" + categoryB + "\"")
        .contains("data-level=\"LEVEL_A\"")
        .contains("data-level=\"LEVEL_C\"")
        .containsPattern("data-level=\"LEVEL_B\"[^>]*data-original=\"Sauber\"")
        .contains("data-testid=\"empty-state\"");
    assertThat(tag(main, "button", "data-pa-select-topic=\"" + topicB + "\""))
        .contains("class=\"master-row pa-topic-row is-active\"")
        .contains("aria-selected=\"true\"");
    assertThat(tag(main, "button", "data-pa-select-topic=\"" + topicA + "\""))
        .contains("aria-selected=\"false\"")
        .doesNotContain("is-active");
  }

  /** Without a topic parameter the first topic is open. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void topicsOpenTheFirstTopicByDefault() throws Exception {
    String html = render("/promotion/admin/topics");

    assertThat(tag(html, "button", "data-pa-select-topic=\"" + topicA + "\""))
        .contains("is-active");
    assertThat(html).containsPattern("id=\"pa-topic-pane-" + topicB + "\"[^>]*hidden");
  }

  /** The requirements render as a matrix whose filled cells carry the highest level. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rankRequirementsRenderAsMatrix() throws Exception {
    when(backendApiClient.get(contains("/api/v1/promotion/rank-requirements"), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(
                    requirement(20, 19, null, "LEVEL_A"),
                    requirement(20, 19, topicB, "LEVEL_B"),
                    requirement(20, 19, topicB, "LEVEL_C"),
                    requirement(19, 18, topicA, "LEVEL_A")),
                0,
                1000,
                4,
                1,
                List.of()));

    String html = render("/promotion/admin/rank-requirements");
    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));

    assertThat(html)
        .containsPattern("class=\"page-eyebrow\"[^>]*>Beförderung<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>4<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContainPattern("class=\"[^\"]*krtm-");
    assertThat(main.split("btn--cta", -1)).hasSize(2);
    assertThat(main)
        .contains("id=\"ar-matrix\"")
        .contains("id=\"ar-group-20-19\"")
        .contains("id=\"ar-group-19-18\"")
        .contains("class=\"data-table data-table--stack ar-rule-table\"")
        .contains("data-ar-req-id=")
        .contains("data-trigger=\"ar-open-edit\"")
        .contains("data-trigger=\"ar-delete-group\"");
    assertThat(main.split("class=\"matrix-flag on\"", -1)).hasSize(4);
    assertThat(tag(main, "button", "data-ar-from=\"20\"", "data-ar-topic-id=\"\""))
        .contains("class=\"matrix-flag on\"")
        .contains("data-trigger=\"ar-matrix-show\"")
        .endsWith(">A");
    assertThat(tag(main, "button", "data-ar-from=\"20\"", "data-ar-topic-id=\"" + topicB + "\""))
        .contains("class=\"matrix-flag on\"")
        .endsWith(">C");
    assertThat(tag(main, "button", "data-ar-from=\"19\"", "data-ar-topic-id=\"\""))
        .contains("class=\"matrix-flag\"")
        .contains("data-trigger=\"ar-matrix-add\"")
        .endsWith(">");
  }

  /** The overview shows the rank steps in career order, the caller's step as the current one. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void overviewRendersRankSteps() throws Exception {
    when(backendApiClient.get(contains("/api/v1/promotion/rank-requirements"), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(
                    requirement(19, 18, topicA, "LEVEL_A"),
                    requirement(21, 20, null, "LEVEL_A"),
                    requirement(20, 19, topicB, "LEVEL_B")),
                0,
                1000,
                3,
                1,
                List.of()));

    String html = render("/promotion/overview");

    assertThat(html)
        .containsPattern("class=\"page-eyebrow\"[^>]*>Beförderung<")
        .contains("class=\"ablauf po-ablauf\"")
        .contains("class=\"level-grid\"")
        .doesNotContain("<details")
        .doesNotContain("hud-box")
        .doesNotContain("class=\"greeting")
        .doesNotContain("promotion-overview.js");
    assertThat(tag(html, "li", "data-po-from-rank=\"21\""))
        .contains("class=\"step po-step step--done\"");
    assertThat(tag(html, "li", "data-po-from-rank=\"20\""))
        .contains("class=\"step po-step step--now\"")
        .contains("aria-current=\"step\"");
    assertThat(tag(html, "li", "data-po-from-rank=\"19\""))
        .contains("class=\"step po-step\"")
        .doesNotContain("aria-current");
    assertThat(html.indexOf("data-po-from-rank=\"21\""))
        .isLessThan(html.indexOf("data-po-from-rank=\"20\""));
    assertThat(html.indexOf("data-po-from-rank=\"20\""))
        .isLessThan(html.indexOf("data-po-from-rank=\"19\""));
  }

  /** "Meine Bewertungen" leads with the progress to the next rank, then one requirement list. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void myEvaluationsLeadWithTheProgressBlock() throws Exception {
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/my"), anyTypeRef()))
        .thenReturn(
            List.of(
                new MemberEvaluationDto(
                    UUID.randomUUID(),
                    0L,
                    "u",
                    categoryB,
                    "Funk",
                    topicB,
                    "Bravo",
                    "LEVEL_A",
                    null,
                    null)));
    when(backendApiClient.get(contains("/api/v1/promotion/rank-requirements"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of()));
    when(backendApiClient.get(contains("/api/v1/promotion/eligibility/my"), anyTypeRef()))
        .thenReturn(
            List.of(
                new PromotionEligibilityDto(
                    "u", 21, 20, true, true, List.of(check(categoryB, 1, 1, true))),
                new PromotionEligibilityDto(
                    "u",
                    20,
                    19,
                    false,
                    true,
                    List.of(check(categoryB, 1, 1, true), check(null, 1, 0, false)))));

    String html = render("/promotion/my-evaluations");

    assertThat(html)
        .containsPattern("class=\"page-eyebrow\"[^>]*>Beförderung<")
        .contains("class=\"card card--accent me-progress\"")
        .contains("class=\"kpi-grid\"")
        .containsPattern("data-testid=\"me-kpi-rank\"[^>]*>20<")
        .containsPattern("data-testid=\"me-kpi-next\"[^>]*>19<")
        .containsPattern("data-testid=\"me-kpi-checks\"[^>]*>1 / 2<")
        .containsPattern("data-testid=\"me-next-status\"[^>]*>Voraussetzungen offen<")
        .contains("data-testid=\"segment-meOpen-all\"")
        .contains("data-testid=\"segment-meOpen-open\"")
        .contains("class=\"data-table data-table--stack me-table\"")
        .contains("data-me-satisfied=\"false\"")
        .doesNotContain("hud-box")
        .doesNotContain("class=\"greeting")
        .doesNotContainPattern("class=\"[^\"]*krtm-");
    assertThat(tag(html, "i", "data-krtm-width="))
        .contains("meter__fill--warning")
        .contains("\"50\"");
    assertThat(tag(html, "tbody", "data-me-from-rank=\"20\""))
        .contains("class=\"me-step is-next\"");
    assertThat(tag(html, "tbody", "data-me-from-rank=\"21\"")).doesNotContain("is-next");
    assertThat(html.indexOf("id=\"me-progress\""))
        .isLessThan(html.indexOf("id=\"me-requirements\""));
    assertThat(html.indexOf("data-me-from-rank=\"20\""))
        .isLessThan(html.indexOf("data-me-from-rank=\"21\""));
  }

  /**
   * Finds the first start tag of the given element that carries every fragment, together with the
   * text that directly follows it.
   *
   * @param html the rendered page
   * @param element the element name, e.g. {@code button}
   * @param fragments attribute snippets the start tag must contain
   * @return the start tag and its leading text, or an empty string when no tag matches
   */
  private static @NotNull String tag(
      @NotNull String html, @NotNull String element, @NotNull String... fragments) {
    Matcher matcher = Pattern.compile("<" + element + "\\b[^>]*>[^<]*").matcher(html);
    while (matcher.find()) {
      String candidate = matcher.group().strip();
      boolean all = true;
      for (String fragment : fragments) {
        all &= candidate.contains(fragment);
      }
      if (all) {
        return candidate;
      }
    }
    return "";
  }

  /**
   * A promotion topic.
   *
   * @param id the topic id
   * @param name the topic name
   * @param sortOrder the topic's position
   * @return the topic
   */
  private static @NotNull PromotionTopicDto topic(
      @NotNull UUID id, @NotNull String name, int sortOrder) {
    return new PromotionTopicDto(id, 2L, name, null, sortOrder, null, null, null);
  }

  /**
   * A rank requirement for a topic or, without one, for all topics.
   *
   * @param fromRank the rank the step starts at
   * @param toRank the rank the step leads to
   * @param topicId the topic, or {@code null} for a global rule
   * @param level the minimum level
   * @return the requirement
   */
  private static @NotNull RankRequirementDto requirement(
      int fromRank, int toRank, @Nullable UUID topicId, @NotNull String level) {
    return new RankRequirementDto(
        UUID.randomUUID(),
        0L,
        fromRank,
        toRank,
        topicId,
        topicId == null ? null : "Topic",
        null,
        null,
        level,
        1,
        null,
        null,
        null);
  }

  /**
   * A requirement check.
   *
   * @param categoryId the checked category, or {@code null} for a topic-wide rule
   * @param required the required count
   * @param achieved the achieved count
   * @param satisfied whether the check is met
   * @return the check
   */
  private static @NotNull PromotionRequirementCheckDto check(
      @Nullable UUID categoryId, int required, int achieved, boolean satisfied) {
    return new PromotionRequirementCheckDto(
        UUID.randomUUID(),
        null,
        "Bravo",
        categoryId,
        categoryId == null ? null : "Funk",
        "LEVEL_A",
        required,
        achieved,
        satisfied,
        null);
  }

  /**
   * The signed-in member with the given rank.
   *
   * @param rank the member's rank
   * @return the member
   */
  private static @NotNull UserDto member(int rank) {
    return new UserDto(
        UUID.randomUUID(),
        "self",
        null,
        "self",
        null,
        rank,
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
}
