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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.MemberEvaluationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionCategoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionEligibilityDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionLevelContentDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionTopicDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RankRequirementDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * Pure-Mockito unit tests for {@link PromotionPageController}.
 *
 * <p>The promotion page controller is a fan-out layer: each handler triggers several {@link
 * BackendApiClient} calls (topics, categories, eligibilities, evaluations, members, the current
 * user) and assembles them into a multi-attribute Thymeleaf model. The tests below verify the
 * *assembly* logic — grouping, key-format conventions, max-reductions across collections, exception
 * swallowing — without exercising Thymeleaf rendering. Rendering itself is templated server-side
 * and indirectly verified by the manual smoke-test plan in the PR description.
 *
 * <p>Because every backend call returns through the same {@code get(uri,
 * ParameterizedTypeReference)} overload, the stubs use {@code contains(...)} on the URI fragment to
 * route each invocation to the right canned reply. This keeps the test setup local to each test and
 * avoids brittle full-URI string matching that would break the moment a query parameter changes.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
class PromotionPageControllerTest {

  @Mock private BackendApiClient backendApiClient;

  @InjectMocks private PromotionPageController controller;

  // ─────────────────────────────────────────────────────────────────────────
  // Test fixtures
  // ─────────────────────────────────────────────────────────────────────────

  private static PromotionTopicDto topic(UUID id, String name, int sortOrder) {
    return new PromotionTopicDto(id, 0L, name, null, sortOrder, null, null);
  }

  private static PromotionCategoryDto category(
      UUID id, UUID topicId, String topicName, String name) {
    return new PromotionCategoryDto(id, 0L, topicId, topicName, name, null, 0, null, null);
  }

  private static RankRequirementDto requirement(
      int fromRank, int toRank, UUID categoryId, String level, int count) {
    return new RankRequirementDto(
        UUID.randomUUID(),
        0L,
        fromRank,
        toRank,
        null,
        null,
        categoryId,
        null,
        level,
        count,
        null,
        null,
        null);
  }

  private static MemberEvaluationDto evaluation(
      String userId, UUID categoryId, String level, Instant updatedAt) {
    return new MemberEvaluationDto(
        UUID.randomUUID(), 0L, userId, categoryId, "Cat", null, "Topic", level, null, updatedAt);
  }

  private static UserDto member(UUID id, String username, Integer rank) {
    return new UserDto(
        id,
        username,
        null,
        username,
        null,
        rank,
        null,
        java.util.Set.of(),
        java.util.Set.of(),
        null,
        null,
        null,
        null,
        null,
        java.util.List.of(),
        0L,
        null,
        false);
  }

  // ─────────────────────────────────────────────────────────────────────────
  // overview()
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  void overview_emptyBackend_populatesEmptyModel() {
    // Backend down for every call → all attributes default to empty/null. The
    // page must still resolve to the overview template so the user sees the
    // empty state instead of a 500.
    when(backendApiClient.get(any(String.class), anyTypeRef())).thenReturn(null);
    Model model = new ConcurrentModel();

    String view = controller.overview(true, model);

    assertEquals("promotion-overview", view);
    assertTrue(((List<?>) model.getAttribute("topics")).isEmpty());
    assertTrue(((Map<?, ?>) model.getAttribute("topicCategoryMap")).isEmpty());
    assertTrue(((Map<?, ?>) model.getAttribute("categoryContentMap")).isEmpty());
    assertTrue(((List<?>) model.getAttribute("rankRequirements")).isEmpty());
    assertTrue(((Map<?, ?>) model.getAttribute("groupedRankRequirements")).isEmpty());
    assertNull(model.getAttribute("currentUserRank"));
  }

  @Test
  void overview_groupsRankRequirementsByFromToPair_andSortsAscending() {
    // Three requirements: two share the 20→19 step, one is 19→18. The page
    // expects the grouped map keyed by `from_to` and ordered ascending so the
    // template can render rank-groups in a stable, predictable order.
    UUID catA = UUID.randomUUID();
    UUID catB = UUID.randomUUID();
    RankRequirementDto r1 = requirement(20, 19, catA, "LEVEL_A", 1);
    RankRequirementDto r2 = requirement(20, 19, catB, "LEVEL_B", 2);
    RankRequirementDto r3 = requirement(19, 18, catA, "LEVEL_C", 1);

    when(backendApiClient.get(contains("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(contains("/api/v1/promotion/rank-requirements"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(r1, r2, r3), 0, 1000, 3, 1, List.of()));
    when(backendApiClient.get(eq("/api/v1/users/me"), anyTypeRef()))
        .thenReturn(member(UUID.randomUUID(), "officer", 19));
    Model model = new ConcurrentModel();

    controller.overview(true, model);

    Map<String, List<RankRequirementDto>> grouped =
        (Map<String, List<RankRequirementDto>>) model.getAttribute("groupedRankRequirements");
    assertNotNull(grouped);
    assertEquals(2, grouped.size(), "two distinct (from,to) pairs");
    // Sort order: 19_18 comes before 20_19 because the comparator sorts by
    // fromRank ascending. The 20_19 bucket holds both r1 and r2 in insertion order.
    List<String> keys = List.copyOf(grouped.keySet());
    assertEquals("19_18", keys.get(0));
    assertEquals("20_19", keys.get(1));
    assertEquals(2, grouped.get("20_19").size());
  }

  @Test
  void overview_currentUserRankPropagatedFromMeEndpoint() {
    when(backendApiClient.get(any(String.class), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/users/me"), anyTypeRef()))
        .thenReturn(member(UUID.randomUUID(), "alice", 12));
    Model model = new ConcurrentModel();

    controller.overview(true, model);

    assertEquals(12, model.getAttribute("currentUserRank"));
  }

  @Test
  void overview_currentUserRankIsNull_whenMeEndpointThrows() {
    // The /me call is wrapped in a try/catch so the page degrades cleanly
    // when the backend is unreachable. We piggy-back this on the empty-state
    // setup to also assert that no other call is needed to land at null.
    when(backendApiClient.get(any(String.class), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/users/me"), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));
    Model model = new ConcurrentModel();

    controller.overview(true, model);

    assertNull(model.getAttribute("currentUserRank"));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // myEvaluations()
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  void myEvaluations_assemblesAllExpectedAttributes() {
    UUID topicId = UUID.randomUUID();
    UUID catId = UUID.randomUUID();
    PromotionTopicDto t = topic(topicId, "Combat", 0);
    PromotionCategoryDto c = category(catId, topicId, "Combat", "Anwesenheit");
    MemberEvaluationDto eval = evaluation("user-1", catId, "LEVEL_B", Instant.now());

    when(backendApiClient.get(contains("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of(t));
    when(backendApiClient.get(contains("/api/v1/promotion/categories/by-topic/"), anyTypeRef()))
        .thenReturn(List.of(c));
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/my"), anyTypeRef()))
        .thenReturn(List.of(eval));
    when(backendApiClient.get(contains("/api/v1/promotion/rank-requirements"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of()));
    when(backendApiClient.get(contains("/api/v1/promotion/eligibility/my"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(eq("/api/v1/users/me"), anyTypeRef()))
        .thenReturn(member(UUID.randomUUID(), "self", 20));
    Model model = new ConcurrentModel();

    String view = controller.myEvaluations(true, model);

    assertEquals("promotion-my-evaluations", view);
    Map<String, MemberEvaluationDto> byCategory =
        (Map<String, MemberEvaluationDto>) model.getAttribute("evaluationByCategoryId");
    assertEquals(1, byCategory.size(), "one evaluation indexed by its categoryId");
    assertEquals(eval, byCategory.get(catId.toString()));
    assertEquals(20, model.getAttribute("currentUserRank"));
  }

  @Test
  void myEvaluations_requiredLevelByCategory_picksHighestLevelAcrossRules() {
    // Two rank requirements target the same category: LEVEL_A (minor step) and
    // LEVEL_C (major step). The strongest demand wins so the template can
    // highlight a weak category against the toughest expectation.
    UUID catId = UUID.randomUUID();
    RankRequirementDto r1 = requirement(20, 19, catId, "LEVEL_A", 1);
    RankRequirementDto r2 = requirement(19, 18, catId, "LEVEL_C", 1);
    RankRequirementDto r3 = requirement(18, 17, catId, "LEVEL_B", 1);

    when(backendApiClient.get(contains("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/my"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(contains("/api/v1/promotion/rank-requirements"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(r1, r2, r3), 0, 1000, 3, 1, List.of()));
    when(backendApiClient.get(contains("/api/v1/promotion/eligibility/my"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(eq("/api/v1/users/me"), anyTypeRef())).thenReturn(null);
    Model model = new ConcurrentModel();

    controller.myEvaluations(true, model);

    Map<String, String> required =
        (Map<String, String>) model.getAttribute("requiredLevelByCategory");
    assertEquals("LEVEL_C", required.get(catId.toString()), "highest level wins");
  }

  @Test
  void myEvaluations_requiredLevelByCategory_skipsTopicWideRules() {
    // A rank requirement without a categoryId is a topic-wide or global rule
    // and must not pollute the per-category required-level map; the template
    // applies the weak-category highlight only when there is a category-
    // specific expectation to compare against.
    UUID catId = UUID.randomUUID();
    RankRequirementDto specific = requirement(20, 19, catId, "LEVEL_B", 1);
    RankRequirementDto topicWide = requirement(20, 19, null, "LEVEL_C", 1);

    when(backendApiClient.get(contains("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/my"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(contains("/api/v1/promotion/rank-requirements"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(specific, topicWide), 0, 1000, 2, 1, List.of()));
    when(backendApiClient.get(contains("/api/v1/promotion/eligibility/my"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(eq("/api/v1/users/me"), anyTypeRef())).thenReturn(null);
    Model model = new ConcurrentModel();

    controller.myEvaluations(true, model);

    Map<String, String> required =
        (Map<String, String>) model.getAttribute("requiredLevelByCategory");
    assertEquals(1, required.size(), "only the category-specific rule contributes");
    assertEquals("LEVEL_B", required.get(catId.toString()));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // manage()
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  void manage_buildsAllCategoriesInTopicOrder_andCategoryCountByTopic() {
    UUID topic1 = UUID.randomUUID();
    UUID topic2 = UUID.randomUUID();
    PromotionCategoryDto c1a = category(UUID.randomUUID(), topic1, "T1", "C1a");
    PromotionCategoryDto c1b = category(UUID.randomUUID(), topic1, "T1", "C1b");
    PromotionCategoryDto c2a = category(UUID.randomUUID(), topic2, "T2", "C2a");

    when(backendApiClient.get(contains("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of(topic(topic1, "T1", 0), topic(topic2, "T2", 1)));
    when(backendApiClient.get(contains("/categories/by-topic/" + topic1 + "/all"), anyTypeRef()))
        .thenReturn(List.of(c1a, c1b));
    when(backendApiClient.get(contains("/categories/by-topic/" + topic2 + "/all"), anyTypeRef()))
        .thenReturn(List.of(c2a));
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/all"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 10000, 0, 0, List.of()));
    when(backendApiClient.get(contains("/api/v1/users?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of()));
    Model model = new ConcurrentModel();

    String view = controller.manage(true, null, null, model);

    assertEquals("promotion-manage", view);
    List<PromotionCategoryDto> all = (List<PromotionCategoryDto>) model.getAttribute("categories");
    assertEquals(List.of(c1a, c1b, c2a), all, "flat list follows topic-then-category order");
    Map<String, Integer> counts = (Map<String, Integer>) model.getAttribute("categoryCountByTopic");
    assertEquals(2, counts.get(topic1.toString()));
    assertEquals(1, counts.get(topic2.toString()));
  }

  @Test
  void manage_evaluationMap_isKeyedByUserIdAndCategoryId() {
    UUID catId = UUID.randomUUID();
    MemberEvaluationDto e1 = evaluation("u1", catId, "LEVEL_A", Instant.now());

    when(backendApiClient.get(contains("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/all"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(e1), 0, 10000, 1, 1, List.of()));
    when(backendApiClient.get(contains("/api/v1/users?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of()));
    Model model = new ConcurrentModel();

    controller.manage(true, null, null, model);

    Map<String, MemberEvaluationDto> map =
        (Map<String, MemberEvaluationDto>) model.getAttribute("evaluationMap");
    assertEquals(1, map.size());
    assertTrue(map.containsKey("u1_" + catId));
  }

  @Test
  void manage_lastEvaluatedByUser_picksMostRecentUpdatedAtPerUser() {
    // Three evaluations for the same user across two categories with
    // ascending timestamps. The map must surface the maximum so the template's
    // "letzte Aenderung am" tooltip is reliable even when an officer edits
    // older categories last.
    UUID catA = UUID.randomUUID();
    UUID catB = UUID.randomUUID();
    Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
    Instant t2 = Instant.parse("2026-02-01T10:00:00Z");
    Instant t3 = Instant.parse("2026-03-01T10:00:00Z");
    MemberEvaluationDto oldA = evaluation("u1", catA, "LEVEL_A", t1);
    MemberEvaluationDto newB = evaluation("u1", catB, "LEVEL_B", t3);
    MemberEvaluationDto midA = evaluation("u1", catA, "LEVEL_C", t2);

    when(backendApiClient.get(contains("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/all"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(oldA, newB, midA), 0, 10000, 3, 1, List.of()));
    when(backendApiClient.get(contains("/api/v1/users?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of()));
    Model model = new ConcurrentModel();

    controller.manage(true, null, null, model);

    Map<String, Instant> last = (Map<String, Instant>) model.getAttribute("lastEvaluatedByUser");
    assertEquals(t3, last.get("u1"), "max(updatedAt) across the user's evaluations");
    Map<String, Boolean> hasEvals =
        (Map<String, Boolean>) model.getAttribute("hasEvaluationsByUser");
    assertEquals(Boolean.TRUE, hasEvals.get("u1"));
  }

  @Test
  void manage_eligibilityByUser_fetchedPerMember_skipsNullIds() {
    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    PromotionEligibilityDto elig1 =
        new PromotionEligibilityDto(id1.toString(), 20, 19, true, true, List.of());
    PromotionEligibilityDto elig2 =
        new PromotionEligibilityDto(id2.toString(), 19, 18, false, true, List.of());
    // The null-id member exists only as a defensive fallback for malformed
    // backend payloads. The controller must skip it entirely rather than
    // crashing with NullPointerException when stringifying the id.
    UserDto nullIdMember = member(null, "phantom", null);

    when(backendApiClient.get(contains("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/all"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 10000, 0, 0, List.of()));
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/members"), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(member(id1, "a", 20), member(id2, "b", 19), nullIdMember),
                0,
                1000,
                3,
                1,
                List.of()));
    when(backendApiClient.get(contains("/api/v1/promotion/eligibility/user/" + id1), anyTypeRef()))
        .thenReturn(List.of(elig1));
    when(backendApiClient.get(contains("/api/v1/promotion/eligibility/user/" + id2), anyTypeRef()))
        .thenReturn(List.of(elig2));
    Model model = new ConcurrentModel();

    controller.manage(true, null, null, model);

    Map<String, List<PromotionEligibilityDto>> byUser =
        (Map<String, List<PromotionEligibilityDto>>) model.getAttribute("eligibilityByUser");
    assertEquals(2, byUser.size(), "null-id member is skipped");
    assertEquals(List.of(elig1), byUser.get(id1.toString()));
    assertEquals(List.of(elig2), byUser.get(id2.toString()));
    // Verify there is no eligibility call for the null-id member: we only
    // expect two per-user eligibility GETs in total.
    verify(backendApiClient, times(2))
        .get(contains("/api/v1/promotion/eligibility/user/"), anyTypeRef());
  }

  @Test
  void manage_emptyMembers_yieldsEmptyEligibilityMap() {
    when(backendApiClient.get(contains("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(contains("/api/v1/promotion/evaluations/all"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 10000, 0, 0, List.of()));
    when(backendApiClient.get(contains("/api/v1/users?"), anyTypeRef())).thenReturn(null);
    Model model = new ConcurrentModel();

    controller.manage(true, null, null, model);

    assertTrue(((Map<?, ?>) model.getAttribute("eligibilityByUser")).isEmpty());
    assertTrue(((List<?>) model.getAttribute("members")).isEmpty());
    assertTrue(
        ((Map<?, ?>) model.getAttribute("hasEvaluationsByUser")).isEmpty(),
        "should remain empty when there are no evaluations");
  }

  // ─────────────────────────────────────────────────────────────────────────
  // adminTopics()
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  void adminTopics_buildsTopicCategoryAndCategoryContentMaps() {
    UUID topicId = UUID.randomUUID();
    UUID catId = UUID.randomUUID();
    PromotionTopicDto t = topic(topicId, "T", 0);
    PromotionCategoryDto c = category(catId, topicId, "T", "C");
    PromotionLevelContentDto lc =
        new PromotionLevelContentDto(
            UUID.randomUUID(), 0L, catId, "C", "LEVEL_A", "desc", null, null);

    when(backendApiClient.get(contains("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of(t));
    when(backendApiClient.get(contains("/categories/by-topic/"), anyTypeRef()))
        .thenReturn(List.of(c));
    when(backendApiClient.get(contains("/level-contents/by-category/"), anyTypeRef()))
        .thenReturn(List.of(lc));
    Model model = new ConcurrentModel();

    String view = controller.adminTopics(true, null, model);

    assertEquals("promotion-admin-topics", view);
    Map<String, List<PromotionCategoryDto>> topicCats =
        (Map<String, List<PromotionCategoryDto>>) model.getAttribute("topicCategoryMap");
    assertEquals(List.of(c), topicCats.get(topicId.toString()));
    Map<String, List<PromotionLevelContentDto>> catContents =
        (Map<String, List<PromotionLevelContentDto>>) model.getAttribute("categoryContentMap");
    assertEquals(List.of(lc), catContents.get(catId.toString()));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // adminRankRequirements()
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  void adminRankRequirements_groupsAndProvidesCascadingMap() {
    UUID topicId = UUID.randomUUID();
    UUID catId = UUID.randomUUID();
    PromotionTopicDto t = topic(topicId, "T", 0);
    PromotionCategoryDto c = category(catId, topicId, "T", "C");
    RankRequirementDto r1 = requirement(20, 19, catId, "LEVEL_A", 1);
    RankRequirementDto r2 = requirement(20, 19, catId, "LEVEL_B", 2);

    when(backendApiClient.get(contains("/api/v1/promotion/rank-requirements"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(r1, r2), 0, 1000, 2, 1, List.of()));
    when(backendApiClient.get(contains("/api/v1/promotion/topics/all"), anyTypeRef()))
        .thenReturn(List.of(t));
    when(backendApiClient.get(contains("/categories/by-topic/" + topicId + "/all"), anyTypeRef()))
        .thenReturn(List.of(c));
    when(backendApiClient.get(contains("/api/v1/promotion/categories?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(c), 0, 1000, 1, 1, List.of()));
    Model model = new ConcurrentModel();

    String view = controller.adminRankRequirements(true, null, model);

    assertEquals("promotion-admin-rank-requirements", view);
    Map<String, List<RankRequirementDto>> grouped =
        (Map<String, List<RankRequirementDto>>) model.getAttribute("groupedRequirements");
    assertEquals(1, grouped.size(), "both requirements share the 20→19 step");
    assertEquals(2, grouped.get("20_19").size());
    Map<String, List<PromotionCategoryDto>> byTopic =
        (Map<String, List<PromotionCategoryDto>>) model.getAttribute("categoriesByTopic");
    assertEquals(List.of(c), byTopic.get(topicId.toString()));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Defensive: every fetch helper swallows backend failures
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  void overview_degradesGracefully_whenEveryBackendCallThrows() {
    // The page must never propagate an exception from the backend. A 500
    // would be worse UX than rendering an empty page with the corner badges
    // and toolbar still in place — the user has a path forward (retry).
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenThrow(new RuntimeException("backend explosion"));
    Model model = new ConcurrentModel();

    String view = controller.overview(true, model);

    assertEquals("promotion-overview", view);
    assertTrue(((List<?>) model.getAttribute("topics")).isEmpty());
    assertNull(model.getAttribute("currentUserRank"));
  }
}
