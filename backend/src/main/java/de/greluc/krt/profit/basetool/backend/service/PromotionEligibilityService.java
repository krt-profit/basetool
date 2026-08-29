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

import de.greluc.krt.profit.basetool.backend.model.MemberEvaluation;
import de.greluc.krt.profit.basetool.backend.model.PromotionCategory;
import de.greluc.krt.profit.basetool.backend.model.PromotionLevel;
import de.greluc.krt.profit.basetool.backend.model.RankRequirement;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionEligibilityResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionRequirementCheckResponse;
import de.greluc.krt.profit.basetool.backend.repository.MemberEvaluationRepository;
import de.greluc.krt.profit.basetool.backend.repository.RankRequirementRepository;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates whether a member fulfils the promotion requirements for one or all configured rank
 * transitions.
 *
 * <p>Each {@code RankRequirement} is either:
 *
 * <ul>
 *   <li><strong>category-scoped</strong> (a specific {@code PromotionCategory} must reach at least
 *       {@code minimumLevel}); or
 *   <li><strong>topic-scoped</strong> ({@code requiredCount} categories belonging to the same
 *       {@code PromotionTopic} must each reach at least {@code minimumLevel}); or
 *   <li><strong>global</strong> (neither topic nor category set – {@code requiredCount} categories
 *       anywhere must reach the level; used as a fallback).
 * </ul>
 *
 * <p>Category-scoped and global requirements are evaluated independently and may freely overlap
 * with topic aggregates – e.g. a LEVEL_A in Anwesenheit simultaneously satisfies the category-
 * scoped "Anwesenheit must be LEVEL_A" rule and counts towards the topic-scoped "Grundlagen
 * requires 2× LEVEL_A" rule.
 *
 * <p>Topic-scoped requirements within the <em>same topic</em>, however, are matched
 * <strong>disjointly</strong>: each evaluated category may count towards at most one topic-scoped
 * rule of that topic. The matching is greedy with strictest-minimum-level first ({@code LEVEL_C}
 * before {@code LEVEL_B} before {@code LEVEL_A}), which is optimal because a higher-level category
 * can fill both stricter and looser slots, so giving the stricter slot first pick never wastes a
 * higher-level category on a slot a lower-level one could have covered. Without this, two rules
 * like "3× LEVEL_B in Grundlagen" + "1× LEVEL_A in another Grundlagen category" would both succeed
 * on the same three B-rated categories – but the second rule's "in another category" intent demands
 * a fourth distinct category.
 *
 * <p>Read-only by design: the service never persists state. Personal queries are filtered by JWT
 * sub at the call site; the {@code *ForUser} methods that take an arbitrary userId are gated by
 * {@code ADMIN} or {@code OFFICER}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PromotionEligibilityService {

  private final RankRequirementRepository rankRequirementRepository;
  private final MemberEvaluationRepository memberEvaluationRepository;
  private final OwnerScopeService ownerScopeService;

  /**
   * Evaluates the eligibility of the given user for one specific rank transition.
   *
   * <p>Returns a response with {@code eligible == false} and {@code hasConfiguredRules == false}
   * when no requirement is configured for the transition, so the UI can distinguish "missing
   * configuration" from "configured but not met".
   *
   * @param userId the {@code app_user.id} of the member being evaluated
   * @param fromRank the rank the member currently holds
   * @param toRank the rank the member would be promoted to
   * @return the per-rule outcome plus an aggregate {@code eligible} flag
   */
  public PromotionEligibilityResponse evaluateForRanks(
      @NotNull UUID userId, int fromRank, int toRank) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return new PromotionEligibilityResponse(userId, fromRank, toRank, false, false, List.of());
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return evaluateForRanks(userId, fromRank, toRank, scope, loadEvaluationIndex(userId, scope));
  }

  /**
   * Core transition evaluation that reuses a pre-loaded {@link EvaluationIndex}. The member's
   * evaluation set is constant for a given {@code (userId, scope)}, so {@link
   * #evaluateAllForUser(String)} loads it once and passes it into every transition instead of
   * re-querying it 2×T times. The public {@link #evaluateForRanks(String, int, int)} owns the gate
   * check and builds the index for a single transition.
   *
   * @param userId the member's {@code app_user.id}.
   * @param fromRank the source rank.
   * @param toRank the target rank.
   * @param scope the promotion scope (squadron id) the requirements and evaluation are read in.
   * @param index the member's pre-loaded assigned-level and category→topic maps.
   * @return the per-rule outcome plus the aggregate {@code eligible} flag.
   */
  private PromotionEligibilityResponse evaluateForRanks(
      @NotNull UUID userId,
      int fromRank,
      int toRank,
      @Nullable UUID scope,
      @NotNull EvaluationIndex index) {
    List<RankRequirement> requirements =
        rankRequirementRepository.findAllForRankTransitionWithRelationsScoped(
            fromRank, toRank, scope);
    Map<UUID, PromotionLevel> levelByCategory = index.levelByCategory();
    Map<UUID, UUID> topicByCategory = index.topicByCategory();

    Map<UUID, PromotionRequirementCheckResponse> topicScopedResults =
        evaluateTopicScopedDisjoint(requirements, levelByCategory, topicByCategory);

    List<PromotionRequirementCheckResponse> checks = new ArrayList<>(requirements.size());
    boolean allSatisfied = true;
    for (RankRequirement req : requirements) {
      PromotionRequirementCheckResponse check = topicScopedResults.get(req.getId());
      if (check == null) {
        check = evaluateRequirement(req, levelByCategory);
      }
      checks.add(check);
      if (!check.satisfied()) {
        allSatisfied = false;
      }
    }

    boolean hasRules = !requirements.isEmpty();
    boolean eligible = hasRules && allSatisfied;
    return new PromotionEligibilityResponse(userId, fromRank, toRank, eligible, hasRules, checks);
  }

  /**
   * Evaluates the user against every {@code (fromRank, toRank)} transition that has at least one
   * configured requirement. The result is ordered the same way as {@link
   * RankRequirementRepository#findDistinctRankTransitions()} – senior transitions first – so the UI
   * can show the next reachable promotion at the top.
   *
   * @param userId the {@code app_user.id} of the member being evaluated
   * @return eligibility entries for every configured transition, possibly empty
   */
  public List<PromotionEligibilityResponse> evaluateAllForUser(@NotNull UUID userId) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return List.of();
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    // Evaluation set + category→topic index are (userId, scope)-constant: load once and share
    // across every transition instead of re-querying 2× per transition (REQ-DATA-003).
    EvaluationIndex index = loadEvaluationIndex(userId, scope);
    List<Object[]> transitions = rankRequirementRepository.findDistinctRankTransitionsScoped(scope);
    List<PromotionEligibilityResponse> result = new ArrayList<>(transitions.size());
    for (Object[] row : transitions) {
      int from = ((Number) row[0]).intValue();
      int to = ((Number) row[1]).intValue();
      result.add(evaluateForRanks(userId, from, to, scope, index));
    }
    return result;
  }

  /**
   * Admin/officer view: same as {@link #evaluateAllForUser(String)} but explicitly callable for any
   * user id. Authorisation is enforced via {@code @PreAuthorize} so personal views keep using
   * {@link #evaluateAllForUser(String)} without a role check.
   *
   * @param userId the {@code app_user.id} of the member being evaluated
   * @return eligibility entries for every configured transition, possibly empty
   */
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public List<PromotionEligibilityResponse> evaluateAllForUserAsAdmin(@NotNull UUID userId) {
    return evaluateAllForUser(userId);
  }

  /**
   * The member's per-(user, scope) evaluation projection: the assigned promotion level per category
   * and the owning topic per category. Both maps are derived from a single pass over the member's
   * evaluation rows.
   *
   * @param levelByCategory category id → the level the member is assigned in it.
   * @param topicByCategory category id → the topic the category belongs to.
   */
  private record EvaluationIndex(
      Map<UUID, PromotionLevel> levelByCategory, Map<UUID, UUID> topicByCategory) {}

  /**
   * Loads the member's evaluation rows once and derives both the assigned-level-by-category and the
   * category→topic maps in a single pass. Previously each of those two maps was built by its own
   * full scan of the identical {@code findAllByUserIdWithCategoryAndTopicScoped} query — two
   * round-trips per call, multiplied across every rank transition; this collapses them to one
   * (REQ-DATA-003).
   *
   * @param userId the member's {@code app_user.id}.
   * @param scope the promotion scope (squadron id) to read the evaluation in, or {@code null}.
   * @return the assigned-level and category→topic maps for the member.
   */
  private EvaluationIndex loadEvaluationIndex(@NotNull UUID userId, @Nullable UUID scope) {
    Map<UUID, PromotionLevel> levels = new HashMap<>();
    Map<UUID, UUID> topics = new HashMap<>();
    for (MemberEvaluation evaluation :
        memberEvaluationRepository.findAllByUserIdWithCategoryAndTopicScoped(userId, scope)) {
      PromotionCategory category = evaluation.getCategory();
      if (category == null) {
        continue;
      }
      if (evaluation.getAssignedLevel() != null) {
        levels.put(category.getId(), evaluation.getAssignedLevel());
      }
      if (category.getTopic() != null) {
        topics.put(category.getId(), category.getTopic().getId());
      }
    }
    return new EvaluationIndex(levels, topics);
  }

  /**
   * Evaluates a category-scoped or global requirement independently of any other rule. Topic-
   * scoped requirements must NOT reach this method; they are pre-resolved by {@link
   * #evaluateTopicScopedDisjoint} so the greedy disjoint matching across topic siblings is
   * respected. The defensive {@link IllegalStateException} below exists to surface a regression
   * immediately if a future change ever bypasses that pre-pass — silently falling back to
   * independent topic evaluation would re-introduce the bug the disjoint matching exists to fix.
   *
   * @param req the rank requirement to evaluate
   * @param levelByCategory the member's assigned levels keyed by category id
   * @return the per-rule outcome
   * @throws IllegalStateException if {@code req} is topic-scoped (must go through {@link
   *     #evaluateTopicScopedDisjoint})
   */
  private PromotionRequirementCheckResponse evaluateRequirement(
      @NotNull RankRequirement req, @NotNull Map<UUID, PromotionLevel> levelByCategory) {
    PromotionLevel minimum = req.getMinimumLevel();
    if (req.getCategory() != null) {
      return evaluateCategoryRequirement(req, minimum, levelByCategory);
    }
    if (req.getTopic() != null) {
      throw new IllegalStateException(
          "Topic-scoped requirement %s must be evaluated via evaluateTopicScopedDisjoint"
              .formatted(req.getId()));
    }
    return evaluateGlobalRequirement(req, minimum, levelByCategory);
  }

  /**
   * Resolves all topic-scoped requirements in {@code requirements} via greedy disjoint matching,
   * grouped per topic. Within a topic the rules are sorted strictest-minimum-level first ({@code
   * LEVEL_C} before {@code LEVEL_B} before {@code LEVEL_A}); the algorithm then walks the sorted
   * list and reserves the first {@code requiredCount} unreserved categories that reach the minimum.
   * Each category is consumed by at most one topic-scoped rule of the same topic.
   *
   * <p>Strictest-first is optimal: a category with a higher level can fill both stricter and looser
   * slots, so giving the stricter slot first pick never wastes a higher-level category on a slot a
   * lower-level one could have covered. Ties on {@code minimumLevel} fall back to the stable
   * repository order so admins' rule ordering is not silently re-arranged.
   *
   * <p>Requirements that are <em>not</em> topic-scoped (category-scoped or global) are skipped here
   * and resolved separately by {@link #evaluateRequirement}; those overlap freely with the topic
   * aggregate, as pinned by the "Anwesenheit A counts towards 2A" scenario.
   *
   * @param requirements all rank requirements for the transition, in repository order
   * @param levelByCategory the member's assigned levels keyed by category id
   * @param topicByCategory category-to-topic index for the categories the member has evaluated
   * @return a map from requirement id to its disjoint-matched check result; non-topic-scoped
   *     requirements are absent from the map and must be evaluated separately
   */
  private Map<UUID, PromotionRequirementCheckResponse> evaluateTopicScopedDisjoint(
      @NotNull List<RankRequirement> requirements,
      @NotNull Map<UUID, PromotionLevel> levelByCategory,
      @NotNull Map<UUID, UUID> topicByCategory) {
    Map<UUID, List<RankRequirement>> byTopic = new LinkedHashMap<>();
    for (RankRequirement req : requirements) {
      if (req.getCategory() == null && req.getTopic() != null) {
        byTopic.computeIfAbsent(req.getTopic().getId(), k -> new ArrayList<>()).add(req);
      }
    }

    Map<UUID, PromotionRequirementCheckResponse> resultByRequirementId = new HashMap<>();
    for (Map.Entry<UUID, List<RankRequirement>> entry : byTopic.entrySet()) {
      UUID topicId = entry.getKey();
      List<RankRequirement> topicReqs = new ArrayList<>(entry.getValue());
      topicReqs.sort(
          Comparator.comparingInt((RankRequirement r) -> r.getMinimumLevel().ordinal()).reversed());

      Set<UUID> reservedCategories = new HashSet<>();
      for (RankRequirement req : topicReqs) {
        PromotionLevel minimum = req.getMinimumLevel();
        int required = req.getRequiredCount();
        int achieved = 0;
        for (Map.Entry<UUID, PromotionLevel> categoryEntry : levelByCategory.entrySet()) {
          if (achieved >= required) {
            break;
          }
          UUID categoryId = categoryEntry.getKey();
          if (reservedCategories.contains(categoryId)) {
            continue;
          }
          UUID owningTopicId = topicByCategory.get(categoryId);
          if (owningTopicId == null || !owningTopicId.equals(topicId)) {
            continue;
          }
          if (!categoryEntry.getValue().isAtLeast(minimum)) {
            continue;
          }
          reservedCategories.add(categoryId);
          achieved++;
        }
        boolean satisfied = achieved >= required;
        resultByRequirementId.put(
            req.getId(),
            new PromotionRequirementCheckResponse(
                req.getId(),
                topicId,
                req.getTopic().getName(),
                null,
                null,
                minimum,
                required,
                achieved,
                satisfied,
                req.getDescription()));
      }
    }
    return resultByRequirementId;
  }

  private PromotionRequirementCheckResponse evaluateCategoryRequirement(
      @NotNull RankRequirement req,
      @NotNull PromotionLevel minimum,
      @NotNull Map<UUID, PromotionLevel> levelByCategory) {
    PromotionCategory category = req.getCategory();
    PromotionLevel userLevel = levelByCategory.get(category.getId());
    boolean satisfied = userLevel != null && userLevel.isAtLeast(minimum);
    UUID topicId = category.getTopic() != null ? category.getTopic().getId() : null;
    String topicName = category.getTopic() != null ? category.getTopic().getName() : null;
    return new PromotionRequirementCheckResponse(
        req.getId(),
        topicId,
        topicName,
        category.getId(),
        category.getName(),
        minimum,
        1,
        satisfied ? 1 : 0,
        satisfied,
        req.getDescription());
  }

  private PromotionRequirementCheckResponse evaluateGlobalRequirement(
      @NotNull RankRequirement req,
      @NotNull PromotionLevel minimum,
      @NotNull Map<UUID, PromotionLevel> levelByCategory) {
    int achieved = 0;
    for (PromotionLevel level : levelByCategory.values()) {
      if (level.isAtLeast(minimum)) {
        achieved++;
      }
    }
    boolean satisfied = achieved >= req.getRequiredCount();
    return new PromotionRequirementCheckResponse(
        req.getId(),
        null,
        null,
        null,
        null,
        minimum,
        req.getRequiredCount(),
        achieved,
        satisfied,
        req.getDescription());
  }
}
