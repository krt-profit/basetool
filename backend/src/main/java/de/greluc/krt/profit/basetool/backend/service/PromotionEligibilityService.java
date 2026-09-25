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
 * transitions. Read-only.
 *
 * <p>A requirement is category-scoped, topic-scoped or global. Category-scoped and global rules are
 * evaluated independently; topic-scoped rules of the same topic are matched disjointly, so each
 * category counts towards at most one of them.
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
   * Evaluates the eligibility of the given user for one rank transition; {@code hasConfiguredRules}
   * is {@code false} when no requirement is configured for it.
   *
   * @param userId the {@code app_user.id} of the member being evaluated
   * @param fromRank the rank the member currently holds
   * @param toRank the rank the member would be promoted to
   * @return the per-rule outcome plus an aggregate {@code eligible} flag
   */
  @NotNull
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
   * Evaluates one rank transition against a pre-loaded {@link EvaluationIndex}, without the feature
   * gate check.
   *
   * @param userId the member's {@code app_user.id}
   * @param fromRank the source rank
   * @param toRank the target rank
   * @param scope the promotion scope (squadron id) the requirements and evaluation are read in
   * @param index the member's pre-loaded assigned-level and category-to-topic maps
   * @return the per-rule outcome plus the aggregate {@code eligible} flag
   */
  @NotNull
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
   * Evaluates the user against every rank transition with at least one configured requirement,
   * senior transitions first.
   *
   * @param userId the {@code app_user.id} of the member being evaluated
   * @return eligibility entries for every configured transition, possibly empty
   */
  @NotNull
  public List<PromotionEligibilityResponse> evaluateAllForUser(@NotNull UUID userId) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return List.of();
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
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
   * Evaluates every configured rank transition for any user; restricted to ADMIN or OFFICER.
   *
   * @param userId the {@code app_user.id} of the member being evaluated
   * @return eligibility entries for every configured transition, possibly empty
   */
  @NotNull
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public List<PromotionEligibilityResponse> evaluateAllForUserAsAdmin(@NotNull UUID userId) {
    return evaluateAllForUser(userId);
  }

  /**
   * The member's assigned promotion level per category and owning topic per category, for one user
   * and scope.
   *
   * @param levelByCategory category id to the level the member is assigned in it
   * @param topicByCategory category id to the topic the category belongs to
   */
  private record EvaluationIndex(
      Map<UUID, PromotionLevel> levelByCategory, Map<UUID, UUID> topicByCategory) {}

  /**
   * Loads the member's evaluation rows once and derives both index maps in a single pass
   * (REQ-DATA-003).
   *
   * @param userId the member's {@code app_user.id}
   * @param scope the promotion scope (squadron id) to read the evaluation in, or {@code null}
   * @return the assigned-level and category-to-topic maps for the member
   */
  @NotNull
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
   * Evaluates a category-scoped or global requirement on its own.
   *
   * @param req the rank requirement to evaluate
   * @param levelByCategory the member's assigned levels keyed by category id
   * @return the per-rule outcome
   * @throws IllegalStateException if {@code req} is topic-scoped (must go through {@link
   *     #evaluateTopicScopedDisjoint})
   */
  @NotNull
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
   * Resolves the topic-scoped requirements by greedy disjoint matching per topic: rules are taken
   * strictest minimum level first (ties in repository order), and each reserves the first {@code
   * requiredCount} unreserved categories that reach its level.
   *
   * @param requirements all rank requirements for the transition, in repository order
   * @param levelByCategory the member's assigned levels keyed by category id
   * @param topicByCategory category-to-topic index for the categories the member has evaluated
   * @return a map from requirement id to its check result; requirements that are not topic-scoped
   *     are absent
   */
  @NotNull
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

  @NotNull
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

  @NotNull
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
