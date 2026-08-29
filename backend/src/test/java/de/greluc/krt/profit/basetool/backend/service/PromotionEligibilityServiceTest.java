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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.MemberEvaluation;
import de.greluc.krt.profit.basetool.backend.model.PromotionCategory;
import de.greluc.krt.profit.basetool.backend.model.PromotionLevel;
import de.greluc.krt.profit.basetool.backend.model.PromotionTopic;
import de.greluc.krt.profit.basetool.backend.model.RankRequirement;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionEligibilityResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionRequirementCheckResponse;
import de.greluc.krt.profit.basetool.backend.repository.MemberEvaluationRepository;
import de.greluc.krt.profit.basetool.backend.repository.RankRequirementRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PromotionEligibilityService}. Covers the three requirement scopes
 * (category, topic, global), missing-level fallback and the "Anwesenheit-A counts towards 2A" rule
 * that motivated the design.
 */
@ExtendWith(MockitoExtension.class)
class PromotionEligibilityServiceTest {

  @Mock private RankRequirementRepository rankRequirementRepository;
  @Mock private MemberEvaluationRepository memberEvaluationRepository;

  @Mock private OwnerScopeService ownerScopeService;

  /**
   * Default-on the per-squadron promotion-feature flag so the existing fixtures that exercise the
   * "happy path" do not get short-circuited to empty by the new gate. {@code lenient()} keeps
   * Mockito from failing tests that never trigger the gate.
   */
  @BeforeEach
  void enablePromotionFeatureFlag() {
    lenient().when(ownerScopeService.isPromotionFeatureEnabledForCurrentScope()).thenReturn(true);
    lenient().when(ownerScopeService.hasPromotionReadAccess()).thenReturn(true);
    // No active pin in these unit tests → null scope → the scoped finders behave like the old
    // unscoped ones, which keeps every eligibility scenario below focused on the matching logic.
    lenient().when(ownerScopeService.currentSquadronId()).thenReturn(Optional.empty());
  }

  @InjectMocks private PromotionEligibilityService service;

  private static final UUID USER = UUID.fromString("0e000009-0000-4000-8000-000000000009");

  @Test
  void evaluateForRanks_shouldReturnNotEligibleWithNoRules_whenNoRequirementsConfigured() {
    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(
            anyInt(), anyInt(), isNull()))
        .thenReturn(List.of());
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(List.of());

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 20, 19);

    assertEquals(20, result.fromRank());
    assertEquals(19, result.toRank());
    assertFalse(result.eligible(), "no rules => not eligible");
    assertFalse(result.hasConfiguredRules(), "no rules => no rules flag");
    assertTrue(result.checks().isEmpty());
  }

  @Test
  void evaluateForRanks_shouldSatisfyCategoryRequirement_whenMemberHasMatchingLevel() {
    // Given – "Anwesenheit must be at least LEVEL_A" rule for 20 -> 19
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionCategory anwesenheit = category(grundlagen, "Anwesenheit");
    RankRequirement req = categoryRule(anwesenheit, PromotionLevel.LEVEL_A, "Anwesenheit A");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(20, 19, null))
        .thenReturn(List.of(req));
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(List.of(evaluation(USER, anwesenheit, PromotionLevel.LEVEL_A)));

    // When
    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 20, 19);

    // Then
    assertTrue(result.eligible());
    assertTrue(result.hasConfiguredRules());
    assertEquals(1, result.checks().size());
    PromotionRequirementCheckResponse check = result.checks().get(0);
    assertTrue(check.satisfied());
    assertEquals(1, check.requiredCount());
    assertEquals(1, check.achievedCount());
    assertEquals(anwesenheit.getId(), check.categoryId());
  }

  @Test
  void evaluateForRanks_shouldFailCategoryRequirement_whenLevelTooLow() {
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionCategory anwesenheit = category(grundlagen, "Anwesenheit");
    // Demand LEVEL_B but member has LEVEL_A
    RankRequirement req = categoryRule(anwesenheit, PromotionLevel.LEVEL_B, "Anwesenheit B");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(19, 18, null))
        .thenReturn(List.of(req));
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(List.of(evaluation(USER, anwesenheit, PromotionLevel.LEVEL_A)));

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 19, 18);

    assertFalse(result.eligible());
    assertFalse(result.checks().get(0).satisfied());
    assertEquals(0, result.checks().get(0).achievedCount());
  }

  @Test
  void evaluateForRanks_shouldCountCategoriesInTopic_whenTopicScopedRequirement() {
    // Given – 2× LEVEL_A in topic "Grundlagen"
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionCategory flug = category(grundlagen, "Flug Kenntnisse");
    PromotionCategory schiff = category(grundlagen, "Schiffsbetrieb");
    PromotionCategory anwesenheit = category(grundlagen, "Anwesenheit");
    RankRequirement req = topicRule(grundlagen, PromotionLevel.LEVEL_A, 2, "2A in Grundlagen");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(20, 19, null))
        .thenReturn(List.of(req));
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(
            List.of(
                evaluation(USER, flug, PromotionLevel.LEVEL_A),
                evaluation(USER, schiff, PromotionLevel.LEVEL_B),
                evaluation(USER, anwesenheit, PromotionLevel.LEVEL_A)));

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 20, 19);

    // Disjoint matching reserves exactly `requiredCount` categories and stops – achievedCount
    // therefore caps at requiredCount. The third A-category remains unreserved (no other rule
    // claims it). Aggregate eligibility is still satisfied.
    assertTrue(result.eligible());
    PromotionRequirementCheckResponse check = result.checks().get(0);
    assertEquals(2, check.achievedCount());
    assertEquals(2, check.requiredCount());
    assertEquals(grundlagen.getId(), check.topicId());
    assertNull(check.categoryId(), "topic-scoped check carries no category");
  }

  @Test
  void evaluateForRanks_shouldFailTopicRequirement_whenNotEnoughCategoriesReachLevel() {
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionCategory flug = category(grundlagen, "Flug Kenntnisse");
    // Want 3× LEVEL_B but only one category reaches it.
    RankRequirement req = topicRule(grundlagen, PromotionLevel.LEVEL_B, 3, "3B in Grundlagen");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(19, 18, null))
        .thenReturn(List.of(req));
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(List.of(evaluation(USER, flug, PromotionLevel.LEVEL_B)));

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 19, 18);

    assertFalse(result.eligible());
    assertEquals(1, result.checks().get(0).achievedCount());
    assertEquals(3, result.checks().get(0).requiredCount());
  }

  @Test
  void evaluateForRanks_shouldCountAnwesenheitAIntoTopicAggregate() {
    // The motivating scenario from the requirements:
    // "Grundlagen mindestens 2A und die darin befindliche Kategorie Anwesenheit muss
    // mindestens A sein – Anwesenheit-A zählt in die 2A hinein."
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionCategory flug = category(grundlagen, "Flug Kenntnisse");
    PromotionCategory anwesenheit = category(grundlagen, "Anwesenheit");

    RankRequirement topicRule =
        topicRule(grundlagen, PromotionLevel.LEVEL_A, 2, "2A in Grundlagen");
    RankRequirement categoryRule =
        categoryRule(anwesenheit, PromotionLevel.LEVEL_A, "Anwesenheit A");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(20, 19, null))
        .thenReturn(List.of(topicRule, categoryRule));
    // Member has exactly 2 A-level entries: one for Flug, one for Anwesenheit
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(
            List.of(
                evaluation(USER, flug, PromotionLevel.LEVEL_A),
                evaluation(USER, anwesenheit, PromotionLevel.LEVEL_A)));

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 20, 19);

    // Both rules must succeed – Anwesenheit-A satisfies the category rule AND counts
    // towards the topic-aggregate "2A in Grundlagen".
    assertTrue(result.eligible(), "Anwesenheit A counts towards the 2A topic aggregate");
    assertEquals(2, result.checks().size());
    for (PromotionRequirementCheckResponse check : result.checks()) {
      assertTrue(check.satisfied());
    }
  }

  @Test
  void evaluateForRanks_shouldFailWhenCategoryRuleMissesEvenIfTopicAggregateMet() {
    // The category rule binds Anwesenheit specifically. Two A's elsewhere don't help.
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionCategory flug = category(grundlagen, "Flug Kenntnisse");
    PromotionCategory schiff = category(grundlagen, "Schiffsbetrieb");
    PromotionCategory anwesenheit = category(grundlagen, "Anwesenheit");

    RankRequirement topicRule =
        topicRule(grundlagen, PromotionLevel.LEVEL_A, 2, "2A in Grundlagen");
    RankRequirement anwesenheitRule =
        categoryRule(anwesenheit, PromotionLevel.LEVEL_A, "Anwesenheit A");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(20, 19, null))
        .thenReturn(List.of(topicRule, anwesenheitRule));
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(
            List.of(
                evaluation(USER, flug, PromotionLevel.LEVEL_A),
                evaluation(USER, schiff, PromotionLevel.LEVEL_A)
                // Anwesenheit unset
                ));

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 20, 19);

    assertFalse(result.eligible(), "Anwesenheit rule fails even when 2A elsewhere is met");
    PromotionRequirementCheckResponse topicCheck =
        result.checks().stream().filter(c -> c.categoryId() == null).findFirst().orElseThrow();
    assertTrue(topicCheck.satisfied(), "topic aggregate still meets 2A");
    PromotionRequirementCheckResponse categoryCheck =
        result.checks().stream().filter(c -> c.categoryId() != null).findFirst().orElseThrow();
    assertFalse(categoryCheck.satisfied(), "category-specific rule fails – Anwesenheit unset");
  }

  @Test
  void evaluateForRanks_shouldRespectLevelOrdering_AisLowestCisHighest() {
    // A member with LEVEL_C should satisfy a "minimum LEVEL_A" rule (higher counts for lower).
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionCategory flug = category(grundlagen, "Flug Kenntnisse");
    RankRequirement req = categoryRule(flug, PromotionLevel.LEVEL_A, "Flug A");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(20, 19, null))
        .thenReturn(List.of(req));
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(List.of(evaluation(USER, flug, PromotionLevel.LEVEL_C)));

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 20, 19);

    assertTrue(result.eligible(), "C counts as 'at least A'");
  }

  @Test
  void evaluateForRanks_shouldEnforceDisjointMatching_betweenTopicScopedRulesInSameTopic() {
    // The squadron's promotion concept demands: "3× B in Grundlagen-Kategorien plus 1× A in
    // einer ANDEREN Grundlagen-Kategorie" — i.e. the A-rule's category must be distinct from
    // the three B-categories. With only three B-rated categories available, the A-rule must
    // fail (no fourth distinct category) even though all three categories trivially satisfy
    // the "at least A" check on their own.
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionCategory katA = category(grundlagen, "KatA");
    PromotionCategory katB = category(grundlagen, "KatB");
    PromotionCategory katC = category(grundlagen, "KatC");

    RankRequirement threeBRule =
        topicRule(grundlagen, PromotionLevel.LEVEL_B, 3, "3B in Grundlagen");
    RankRequirement oneARule = topicRule(grundlagen, PromotionLevel.LEVEL_A, 1, "1A in Grundlagen");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(20, 19, null))
        .thenReturn(List.of(threeBRule, oneARule));
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(
            List.of(
                evaluation(USER, katA, PromotionLevel.LEVEL_B),
                evaluation(USER, katB, PromotionLevel.LEVEL_B),
                evaluation(USER, katC, PromotionLevel.LEVEL_B)));

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 20, 19);

    assertFalse(result.eligible(), "no fourth distinct category exists for the 1A rule");
    PromotionRequirementCheckResponse bCheck = checkForRequirement(result, threeBRule);
    PromotionRequirementCheckResponse aCheck = checkForRequirement(result, oneARule);
    assertTrue(bCheck.satisfied(), "3B is satisfied by the three B-rated categories");
    assertEquals(3, bCheck.achievedCount());
    assertFalse(aCheck.satisfied(), "1A fails – all three B-categories are reserved for 3B");
    assertEquals(0, aCheck.achievedCount());
  }

  @Test
  void evaluateForRanks_shouldSatisfyDisjointTopicRules_whenFourDistinctCategoriesProvided() {
    // Same setup as the previous test but with a fourth A-rated category. Now the disjoint
    // matching succeeds: 3B consumes the three B-categories, 1A picks up the fourth.
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionCategory katA = category(grundlagen, "KatA");
    PromotionCategory katB = category(grundlagen, "KatB");
    PromotionCategory katC = category(grundlagen, "KatC");
    PromotionCategory katD = category(grundlagen, "KatD");

    RankRequirement threeBRule =
        topicRule(grundlagen, PromotionLevel.LEVEL_B, 3, "3B in Grundlagen");
    RankRequirement oneARule = topicRule(grundlagen, PromotionLevel.LEVEL_A, 1, "1A in Grundlagen");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(20, 19, null))
        .thenReturn(List.of(threeBRule, oneARule));
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(
            List.of(
                evaluation(USER, katA, PromotionLevel.LEVEL_B),
                evaluation(USER, katB, PromotionLevel.LEVEL_B),
                evaluation(USER, katC, PromotionLevel.LEVEL_B),
                evaluation(USER, katD, PromotionLevel.LEVEL_A)));

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 20, 19);

    assertTrue(result.eligible());
    assertTrue(checkForRequirement(result, threeBRule).satisfied());
    assertEquals(3, checkForRequirement(result, threeBRule).achievedCount());
    assertTrue(checkForRequirement(result, oneARule).satisfied());
    assertEquals(1, checkForRequirement(result, oneARule).achievedCount());
  }

  @Test
  void evaluateForRanks_shouldReserveHigherLevelForStricterRule_inDisjointMatching() {
    // Strictest-first ordering: with two C-rated and one A-rated category in the topic, the
    // "2× C" rule must take both C-categories first; the "1× A" rule then picks up the A-cat.
    // Even if the repository returns the rules in (A-rule, C-rule) order, the algorithm sorts
    // by strictness so C wins the C-categories.
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionCategory katA = category(grundlagen, "KatA");
    PromotionCategory katB = category(grundlagen, "KatB");
    PromotionCategory katC = category(grundlagen, "KatC");

    RankRequirement twoCRule = topicRule(grundlagen, PromotionLevel.LEVEL_C, 2, "2C in Grundlagen");
    RankRequirement oneARule = topicRule(grundlagen, PromotionLevel.LEVEL_A, 1, "1A in Grundlagen");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(20, 19, null))
        .thenReturn(List.of(oneARule, twoCRule));
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(
            List.of(
                evaluation(USER, katA, PromotionLevel.LEVEL_C),
                evaluation(USER, katB, PromotionLevel.LEVEL_C),
                evaluation(USER, katC, PromotionLevel.LEVEL_A)));

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 20, 19);

    assertTrue(result.eligible(), "strictest-first reserves the C-cats for 2C, A-cat left for 1A");
    assertEquals(2, checkForRequirement(result, twoCRule).achievedCount());
    assertEquals(1, checkForRequirement(result, oneARule).achievedCount());
  }

  @Test
  void evaluateForRanks_shouldStillSatisfyLooserRule_whenStricterRuleNotFullyMet() {
    // The stricter rule runs out of high-level categories; the looser rule can still claim
    // the remaining ones. Independent evaluation – not "either both or neither".
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionCategory katA = category(grundlagen, "KatA");
    PromotionCategory katB = category(grundlagen, "KatB");

    RankRequirement twoCRule = topicRule(grundlagen, PromotionLevel.LEVEL_C, 2, "2C in Grundlagen");
    RankRequirement oneARule = topicRule(grundlagen, PromotionLevel.LEVEL_A, 1, "1A in Grundlagen");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(20, 19, null))
        .thenReturn(List.of(twoCRule, oneARule));
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(
            List.of(
                evaluation(USER, katA, PromotionLevel.LEVEL_C),
                evaluation(USER, katB, PromotionLevel.LEVEL_A)));

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 20, 19);

    assertFalse(result.eligible(), "2C rule needs two C-cats but only one exists");
    PromotionRequirementCheckResponse cCheck = checkForRequirement(result, twoCRule);
    PromotionRequirementCheckResponse aCheck = checkForRequirement(result, oneARule);
    assertFalse(cCheck.satisfied());
    assertEquals(1, cCheck.achievedCount(), "the single C-cat is reserved for the stricter rule");
    assertTrue(aCheck.satisfied(), "the A-cat remains unreserved and satisfies 1A");
    assertEquals(1, aCheck.achievedCount());
  }

  @Test
  void evaluateForRanks_shouldAllowCategoryRuleToOverlapWithTopicAggregate_underDisjointMatching() {
    // The squadron's full canonical scenario: "3B in Grundlagen + 1A in another Grundlagen
    // category + Anwesenheit (a Grundlagen category) must be B". The category-scoped
    // Anwesenheit rule must NOT consume the Anwesenheit-cat from the topic-scoped pool –
    // otherwise satisfying Anwesenheit-B would reduce the available B-cats for "3B".
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionCategory anwesenheit = category(grundlagen, "Anwesenheit");
    PromotionCategory katA = category(grundlagen, "KatA");
    PromotionCategory katB = category(grundlagen, "KatB");
    PromotionCategory katC = category(grundlagen, "KatC");

    RankRequirement threeBRule =
        topicRule(grundlagen, PromotionLevel.LEVEL_B, 3, "3B in Grundlagen");
    RankRequirement oneARule = topicRule(grundlagen, PromotionLevel.LEVEL_A, 1, "1A in Grundlagen");
    RankRequirement anwesenheitRule =
        categoryRule(anwesenheit, PromotionLevel.LEVEL_B, "Anwesenheit B");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(20, 19, null))
        .thenReturn(List.of(threeBRule, oneARule, anwesenheitRule));
    // Four distinct categories: Anwesenheit (B) + two more B's + one A. The Anwesenheit cat
    // satisfies BOTH the category rule AND counts as one of the three B-cats for "3B".
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(
            List.of(
                evaluation(USER, anwesenheit, PromotionLevel.LEVEL_B),
                evaluation(USER, katA, PromotionLevel.LEVEL_B),
                evaluation(USER, katB, PromotionLevel.LEVEL_B),
                evaluation(USER, katC, PromotionLevel.LEVEL_A)));

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 20, 19);

    assertTrue(result.eligible(), "Anwesenheit-B counts for both the category and topic rules");
    assertEquals(3, checkForRequirement(result, threeBRule).achievedCount());
    assertEquals(1, checkForRequirement(result, oneARule).achievedCount());
    assertEquals(1, checkForRequirement(result, anwesenheitRule).achievedCount());
  }

  @Test
  void evaluateForRanks_shouldKeepDisjointMatchingPerTopic_notAcrossTopics() {
    // Disjointness is a within-topic property. A B-cat in Grundlagen and a B-cat in
    // Spezialisierungen each satisfy their own topic's "1× B" rule – they don't compete
    // because they live in different topics.
    PromotionTopic grundlagen = topic("Grundlagen");
    PromotionTopic spez = topic("Spezialisierungen");
    PromotionCategory grundlagenKat = category(grundlagen, "G-Kat");
    PromotionCategory spezKat = category(spez, "S-Kat");

    RankRequirement grundlagenRule =
        topicRule(grundlagen, PromotionLevel.LEVEL_B, 1, "1B in Grundlagen");
    RankRequirement spezRule =
        topicRule(spez, PromotionLevel.LEVEL_B, 1, "1B in Spezialisierungen");

    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(20, 19, null))
        .thenReturn(List.of(grundlagenRule, spezRule));
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(
            List.of(
                evaluation(USER, grundlagenKat, PromotionLevel.LEVEL_B),
                evaluation(USER, spezKat, PromotionLevel.LEVEL_B)));

    PromotionEligibilityResponse result = service.evaluateForRanks(USER, 20, 19);

    assertTrue(result.eligible(), "disjoint matching is per-topic; both rules satisfied");
    assertEquals(1, checkForRequirement(result, grundlagenRule).achievedCount());
    assertEquals(1, checkForRequirement(result, spezRule).achievedCount());
  }

  @Test
  void evaluateAllForUser_shouldReturnOneEntryPerConfiguredTransition() {
    when(rankRequirementRepository.findDistinctRankTransitionsScoped(null))
        .thenReturn(List.of(new Object[] {20, 19}, new Object[] {19, 18}));
    // Both transitions resolve to empty rules => not eligible / hasConfiguredRules=false
    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(
            anyInt(), anyInt(), isNull()))
        .thenReturn(List.of());
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, null))
        .thenReturn(List.of());

    List<PromotionEligibilityResponse> result = service.evaluateAllForUser(USER);

    assertEquals(2, result.size());
    assertEquals(20, result.get(0).fromRank());
    assertEquals(19, result.get(0).toRank());
    assertEquals(19, result.get(1).fromRank());
    assertEquals(18, result.get(1).toRank());
    // REQ-DATA-003: the member's evaluation set is loaded once for the whole user, not 2× per
    // transition (was 2×T = 4 reads for these two transitions before the dedup).
    verify(memberEvaluationRepository, times(1))
        .findAllByUserIdWithCategoryAndTopicScoped(USER, null);
  }

  @Test
  void evaluateForRanks_shouldScopeRequirementAndEvaluationLoadsToActiveSquadron() {
    // Given: the caller is pinned to a specific squadron.
    UUID scopeId = UUID.randomUUID();
    when(ownerScopeService.currentSquadronId()).thenReturn(Optional.of(scopeId));
    when(rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(20, 19, scopeId))
        .thenReturn(List.of());
    when(memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(USER, scopeId))
        .thenReturn(List.of());

    // When
    service.evaluateForRanks(USER, 20, 19);

    // Then: both the requirement load and the member's grade load are filtered by that squadron,
    // so neither another squadron's rules nor the member's grades there bleed into the result.
    verify(rankRequirementRepository).findAllForRankTransitionWithRelationsScoped(20, 19, scopeId);
    verify(memberEvaluationRepository, atLeastOnce())
        .findAllByUserIdWithCategoryAndTopicScoped(USER, scopeId);
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  private PromotionRequirementCheckResponse checkForRequirement(
      PromotionEligibilityResponse result, RankRequirement req) {
    return result.checks().stream()
        .filter(c -> c.requirementId().equals(req.getId()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "no check returned for requirement "
                        + req.getId()
                        + " ("
                        + req.getDescription()
                        + ")"));
  }

  private PromotionTopic topic(String name) {
    PromotionTopic t = PromotionTopic.builder().name(name).sortOrder(0).build();
    t.setId(UUID.randomUUID());
    return t;
  }

  private PromotionCategory category(PromotionTopic topic, String name) {
    PromotionCategory c = PromotionCategory.builder().topic(topic).name(name).sortOrder(0).build();
    c.setId(UUID.randomUUID());
    return c;
  }

  private MemberEvaluation evaluation(
      UUID userId, PromotionCategory category, PromotionLevel level) {
    MemberEvaluation e =
        MemberEvaluation.builder().userId(userId).category(category).assignedLevel(level).build();
    e.setId(UUID.randomUUID());
    return e;
  }

  private RankRequirement categoryRule(
      PromotionCategory category, PromotionLevel min, String description) {
    RankRequirement r =
        RankRequirement.builder()
            .fromRank(20)
            .toRank(19)
            .category(category)
            .minimumLevel(min)
            .requiredCount(1)
            .description(description)
            .build();
    r.setId(UUID.randomUUID());
    return r;
  }

  private RankRequirement topicRule(
      PromotionTopic topic, PromotionLevel min, int count, String description) {
    RankRequirement r =
        RankRequirement.builder()
            .fromRank(20)
            .toRank(19)
            .topic(topic)
            .minimumLevel(min)
            .requiredCount(count)
            .description(description)
            .build();
    r.setId(UUID.randomUUID());
    return r;
  }
}
