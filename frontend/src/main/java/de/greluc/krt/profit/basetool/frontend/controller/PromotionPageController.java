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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.MemberEvaluationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionCategoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionEligibilityDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionLevelContentDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PromotionTopicDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RankRequirementDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages.CompleteCatalog;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Frontend controller for the promotion system pages. */
@Controller
@UsesLayoutModel
@RequestMapping("/promotion")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class PromotionPageController {

  /** Response type for the {@code /promotion/topics/all} list of promotion topics. */
  private static final ParameterizedTypeReference<List<PromotionTopicDto>> TOPIC_LIST_TYPE =
      new ParameterizedTypeReference<List<PromotionTopicDto>>() {};

  /** Response type for the {@code /promotion/categories/by-topic/{id}/all} category list. */
  private static final ParameterizedTypeReference<List<PromotionCategoryDto>> CATEGORY_LIST_TYPE =
      new ParameterizedTypeReference<List<PromotionCategoryDto>>() {};

  /** Response type for the paged {@code /promotion/categories} listing of all categories. */
  private static final ParameterizedTypeReference<PageResponse<PromotionCategoryDto>>
      CATEGORY_PAGE_TYPE = new ParameterizedTypeReference<PageResponse<PromotionCategoryDto>>() {};

  /**
   * Response type for the {@code /promotion/level-contents/by-category/{id}} level-content list.
   */
  private static final ParameterizedTypeReference<List<PromotionLevelContentDto>>
      LEVEL_CONTENT_LIST_TYPE = new ParameterizedTypeReference<List<PromotionLevelContentDto>>() {};

  /** Response type for the paged {@code /promotion/rank-requirements} listing. */
  private static final ParameterizedTypeReference<PageResponse<RankRequirementDto>>
      RANK_REQUIREMENT_PAGE_TYPE =
          new ParameterizedTypeReference<PageResponse<RankRequirementDto>>() {};

  /** Response type for the {@code /users/me} single-user lookup used to read the caller's rank. */
  private static final ParameterizedTypeReference<
          de.greluc.krt.profit.basetool.frontend.model.dto.UserDto>
      USER_TYPE =
          new ParameterizedTypeReference<
              de.greluc.krt.profit.basetool.frontend.model.dto.UserDto>() {};

  /** Response type for the {@code /promotion/evaluations/my} personal evaluation list. */
  private static final ParameterizedTypeReference<List<MemberEvaluationDto>>
      MEMBER_EVALUATION_LIST_TYPE = new ParameterizedTypeReference<List<MemberEvaluationDto>>() {};

  /** Response type for the paged {@code /promotion/evaluations/all} evaluation listing. */
  private static final ParameterizedTypeReference<PageResponse<MemberEvaluationDto>>
      MEMBER_EVALUATION_PAGE_TYPE =
          new ParameterizedTypeReference<PageResponse<MemberEvaluationDto>>() {};

  /** Response type for the paged {@code /promotion/evaluations/members} squadron-member listing. */
  private static final ParameterizedTypeReference<
          PageResponse<de.greluc.krt.profit.basetool.frontend.model.dto.UserDto>>
      USER_PAGE_TYPE =
          new ParameterizedTypeReference<
              PageResponse<de.greluc.krt.profit.basetool.frontend.model.dto.UserDto>>() {};

  /** Response type for the {@code /promotion/eligibility} promotion-eligibility lists. */
  private static final ParameterizedTypeReference<List<PromotionEligibilityDto>>
      ELIGIBILITY_LIST_TYPE = new ParameterizedTypeReference<List<PromotionEligibilityDto>>() {};

  /**
   * Page size of the {@link CatalogPages#fetchAll page walks} feeding the evaluation matrix; a
   * chunk size, not a cap (REQ-PROMO-001).
   */
  private static final int MATRIX_FETCH_PAGE_SIZE = 1000;

  private final BackendApiClient backendApiClient;

  /**
   * Throws {@link AccessDeniedException}, answered with 403, when the promotion feature is disabled
   * for the caller.
   *
   * @param enabled the {@code promotionFeatureEnabled} model attribute; {@code null} counts as
   *     enabled
   * @throws AccessDeniedException when {@code enabled} is {@code Boolean.FALSE}
   */
  private static void requirePromotionFeature(Boolean enabled) {
    if (Boolean.FALSE.equals(enabled)) {
      throw new AccessDeniedException(
          "Promotion feature is disabled for the caller's squadron; ask an administrator to"
              + " re-enable it.");
    }
  }

  /**
   * Renders the promotion-system overview for every signed-in user.
   *
   * <p>Passes the caller's current rank, or {@code null} when unknown, so the template can mark the
   * caller's next rank step.
   */
  @NotNull
  @GetMapping("/overview")
  public String overview(
      @ModelAttribute("promotionFeatureEnabled") Boolean promotionFeatureEnabled, Model model) {
    requirePromotionFeature(promotionFeatureEnabled);
    List<PromotionTopicDto> topics = fetchTopics();
    Map<String, List<PromotionCategoryDto>> topicCategoryMap = new LinkedHashMap<>();
    Map<String, List<PromotionLevelContentDto>> categoryContentMap = new LinkedHashMap<>();

    for (PromotionTopicDto topic : topics) {
      List<PromotionCategoryDto> categories = fetchCategoriesByTopic(topic.id().toString());
      topicCategoryMap.put(topic.id().toString(), categories);
      for (PromotionCategoryDto category : categories) {
        List<PromotionLevelContentDto> contents = fetchLevelContents(category.id().toString());
        categoryContentMap.put(category.id().toString(), contents);
      }
    }

    List<RankRequirementDto> rankRequirements = fetchAllRankRequirements();
    Map<String, List<RankRequirementDto>> groupedRankRequirements = new LinkedHashMap<>();
    rankRequirements.stream()
        .sorted(
            java.util.Comparator.comparingInt(RankRequirementDto::fromRank)
                .thenComparingInt(RankRequirementDto::toRank))
        .forEach(
            req -> {
              String key = req.fromRank() + "_" + req.toRank();
              groupedRankRequirements.computeIfAbsent(key, k -> new ArrayList<>()).add(req);
            });

    model.addAttribute("topics", topics);
    model.addAttribute("topicCategoryMap", topicCategoryMap);
    model.addAttribute("categoryContentMap", categoryContentMap);
    model.addAttribute("rankRequirements", rankRequirements);
    model.addAttribute("groupedRankRequirements", groupedRankRequirements);
    model.addAttribute("currentUserRank", fetchCurrentUserRank());
    return "promotion-overview";
  }

  /**
   * Renders the caller's own evaluations with the promotion eligibility of every configured rank
   * step.
   *
   * <p>Also computes {@code requiredLevelByCategory}, the highest minimum level any requirement
   * demands per category, so the template can highlight weak categories.
   */
  @NotNull
  @GetMapping("/my-evaluations")
  public String myEvaluations(
      @ModelAttribute("promotionFeatureEnabled") Boolean promotionFeatureEnabled, Model model) {
    requirePromotionFeature(promotionFeatureEnabled);
    List<PromotionTopicDto> topics = fetchTopics();
    List<MemberEvaluationDto> myEvaluations = fetchMyEvaluations();

    Map<String, MemberEvaluationDto> evaluationByCategoryId = new LinkedHashMap<>();
    for (MemberEvaluationDto eval : myEvaluations) {
      evaluationByCategoryId.put(eval.categoryId().toString(), eval);
    }

    Map<String, List<PromotionCategoryDto>> topicCategoryMap = new LinkedHashMap<>();
    for (PromotionTopicDto topic : topics) {
      List<PromotionCategoryDto> categories = fetchCategoriesByTopic(topic.id().toString());
      topicCategoryMap.put(topic.id().toString(), categories);
    }

    Map<String, String> requiredLevelByCategory = new LinkedHashMap<>();
    for (RankRequirementDto req : fetchAllRankRequirements()) {
      if (req.categoryId() == null || req.minimumLevel() == null) {
        continue;
      }
      String key = req.categoryId().toString();
      String existing = requiredLevelByCategory.get(key);
      if (existing == null || compareLevels(req.minimumLevel(), existing) > 0) {
        requiredLevelByCategory.put(key, req.minimumLevel());
      }
    }

    List<PromotionEligibilityDto> eligibilities = fetchMyEligibility();

    model.addAttribute("topics", topics);
    model.addAttribute("topicCategoryMap", topicCategoryMap);
    model.addAttribute("evaluationByCategoryId", evaluationByCategoryId);
    model.addAttribute("eligibilities", eligibilities);
    model.addAttribute("requiredLevelByCategory", requiredLevelByCategory);
    model.addAttribute("currentUserRank", fetchCurrentUserRank());
    return "promotion-my-evaluations";
  }

  /**
   * Compares two promotion levels in {@code PromotionLevel} order; unknown or {@code null} values
   * sort below known ones.
   *
   * @param a first level identifier, e.g. {@code "LEVEL_B"}; may be {@code null}
   * @param b second level identifier; may be {@code null}
   * @return positive iff {@code a} is higher, negative iff lower, zero otherwise
   */
  private int compareLevels(String a, String b) {
    return levelOrdinal(a) - levelOrdinal(b);
  }

  private int levelOrdinal(String level) {
    if (level == null) {
      return -1;
    }
    return switch (level) {
      case "LEVEL_A" -> 0;
      case "LEVEL_B" -> 1;
      case "LEVEL_C" -> 2;
      default -> -1;
    };
  }

  /**
   * Renders the evaluation management page for ADMIN and OFFICER, including each member's promotion
   * eligibility.
   *
   * <p>Also provides {@code categoriesByTopic} and {@code categoryCountByTopic} for the two-row
   * header; the order of {@code topics} matches the column order of {@code allCategories}.
   */
  @NotNull
  @GetMapping("/manage")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public String manage(
      @ModelAttribute("promotionFeatureEnabled") Boolean promotionFeatureEnabled,
      @RequestParam(required = false) String fragment,
      @RequestParam(required = false) String userId,
      Model model) {
    requirePromotionFeature(promotionFeatureEnabled);

    if ("eligibilityCell".equals(fragment) && userId != null && !userId.isBlank()) {
      model.addAttribute("eligList", fetchEligibilityForUser(userId));
      return "promotion-manage :: eligibilityCell";
    }

    List<PromotionTopicDto> topics = fetchTopics();
    List<PromotionCategoryDto> allCategories = new ArrayList<>();
    Map<String, List<PromotionCategoryDto>> categoriesByTopic = new LinkedHashMap<>();
    Map<String, Integer> categoryCountByTopic = new LinkedHashMap<>();
    for (PromotionTopicDto topic : topics) {
      List<PromotionCategoryDto> topicCategories = fetchCategoriesByTopic(topic.id().toString());
      categoriesByTopic.put(topic.id().toString(), topicCategories);
      categoryCountByTopic.put(topic.id().toString(), topicCategories.size());
      allCategories.addAll(topicCategories);
    }

    CompleteCatalog<MemberEvaluationDto> evaluationsCatalog = fetchAllEvaluations();
    List<MemberEvaluationDto> allEvaluations = evaluationsCatalog.items();
    Map<String, MemberEvaluationDto> evaluationMap = new LinkedHashMap<>();
    Map<String, java.time.Instant> lastEvaluatedByUser = new LinkedHashMap<>();
    Map<String, Boolean> hasEvaluationsByUser = new LinkedHashMap<>();
    for (MemberEvaluationDto eval : allEvaluations) {
      evaluationMap.put(eval.userId() + "_" + eval.categoryId(), eval);
      hasEvaluationsByUser.put(eval.userId(), Boolean.TRUE);
      if (eval.updatedAt() != null) {
        java.time.Instant prev = lastEvaluatedByUser.get(eval.userId());
        if (prev == null || eval.updatedAt().isAfter(prev)) {
          lastEvaluatedByUser.put(eval.userId(), eval.updatedAt());
        }
      }
    }

    CompleteCatalog<de.greluc.krt.profit.basetool.frontend.model.dto.UserDto> membersCatalog =
        fetchMembers();
    List<de.greluc.krt.profit.basetool.frontend.model.dto.UserDto> members = membersCatalog.items();

    Map<String, List<PromotionEligibilityDto>> eligibilityByUser = new LinkedHashMap<>();
    for (de.greluc.krt.profit.basetool.frontend.model.dto.UserDto member : members) {
      if (member.id() != null) {
        eligibilityByUser.put(
            member.id().toString(), fetchEligibilityForUser(member.id().toString()));
      }
    }

    model.addAttribute("topics", topics);
    model.addAttribute("categoriesByTopic", categoriesByTopic);
    model.addAttribute("categoryCountByTopic", categoryCountByTopic);
    model.addAttribute("categories", allCategories);
    model.addAttribute("evaluationMap", evaluationMap);
    model.addAttribute("members", members);
    model.addAttribute("eligibilityByUser", eligibilityByUser);
    model.addAttribute("lastEvaluatedByUser", lastEvaluatedByUser);
    model.addAttribute("hasEvaluationsByUser", hasEvaluationsByUser);
    model.addAttribute(
        "matrixTruncated", evaluationsCatalog.truncated() || membersCatalog.truncated());
    if ("matrixBody".equals(fragment)) {
      return "promotion-manage :: matrixBody";
    }
    return "promotion-manage";
  }

  /** Schritt 8: Admin-Bereich – Themenbereiche, Kategorien & Stufeninhalte verwalten. */
  @NotNull
  @GetMapping("/admin/topics")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public String adminTopics(
      @ModelAttribute("promotionFeatureEnabled") Boolean promotionFeatureEnabled,
      @RequestParam(required = false) String fragment,
      Model model) {
    requirePromotionFeature(promotionFeatureEnabled);
    List<PromotionTopicDto> topics = fetchTopics();
    Map<String, List<PromotionCategoryDto>> topicCategoryMap = new LinkedHashMap<>();
    Map<String, List<PromotionLevelContentDto>> categoryContentMap = new LinkedHashMap<>();
    for (PromotionTopicDto topic : topics) {
      List<PromotionCategoryDto> categories = fetchCategoriesByTopic(topic.id().toString());
      topicCategoryMap.put(topic.id().toString(), categories);
      for (PromotionCategoryDto category : categories) {
        categoryContentMap.put(
            category.id().toString(), fetchLevelContents(category.id().toString()));
      }
    }
    model.addAttribute("topics", topics);
    model.addAttribute("topicCategoryMap", topicCategoryMap);
    model.addAttribute("categoryContentMap", categoryContentMap);
    if ("topicsResults".equals(fragment)) {
      return "promotion-admin-topics :: topicsResults";
    }
    return "promotion-admin-topics";
  }

  /**
   * Renders the admin page of rank requirements, grouped into one section per {@code (fromRank,
   * toRank)} pair.
   *
   * @param promotionFeatureEnabled per-squadron feature flag; {@code false} answers 403
   * @param fragment {@code "ranksResults"} renders only the requirements-list fragment
   * @param model model populated with the grouped requirements, topics and categories
   * @return the view name, or its fragment selector
   */
  @NotNull
  @GetMapping("/admin/rank-requirements")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public String adminRankRequirements(
      @ModelAttribute("promotionFeatureEnabled") Boolean promotionFeatureEnabled,
      @RequestParam(required = false) String fragment,
      Model model) {
    requirePromotionFeature(promotionFeatureEnabled);
    List<RankRequirementDto> requirements = fetchAllRankRequirements();

    Map<String, List<RankRequirementDto>> groupedRequirements = new LinkedHashMap<>();
    requirements.stream()
        .sorted(
            java.util.Comparator.comparingInt(RankRequirementDto::fromRank)
                .thenComparingInt(RankRequirementDto::toRank))
        .forEach(
            req -> {
              String key = req.fromRank() + "_" + req.toRank();
              groupedRequirements.computeIfAbsent(key, k -> new ArrayList<>()).add(req);
            });

    List<PromotionTopicDto> topics = fetchTopics();
    Map<String, List<PromotionCategoryDto>> categoriesByTopic = new LinkedHashMap<>();
    for (PromotionTopicDto topic : topics) {
      categoriesByTopic.put(topic.id().toString(), fetchCategoriesByTopic(topic.id().toString()));
    }

    model.addAttribute("requirements", requirements);
    model.addAttribute("groupedRequirements", groupedRequirements);
    model.addAttribute("topics", topics);
    model.addAttribute("categories", fetchAllCategories());
    model.addAttribute("categoriesByTopic", categoriesByTopic);
    if ("ranksResults".equals(fragment)) {
      return "promotion-admin-rank-requirements :: ranksResults";
    }
    return "promotion-admin-rank-requirements";
  }

  private List<PromotionTopicDto> fetchTopics() {
    try {
      List<PromotionTopicDto> result =
          backendApiClient.get("/api/v1/promotion/topics/all", TOPIC_LIST_TYPE);
      return result != null ? result : new ArrayList<>();
    } catch (Exception e) {
      log.error("Failed to fetch promotion topics", e);
      return new ArrayList<>();
    }
  }

  private List<PromotionCategoryDto> fetchCategoriesByTopic(String topicId) {
    try {
      List<PromotionCategoryDto> result =
          backendApiClient.get(
              "/api/v1/promotion/categories/by-topic/" + topicId + "/all", CATEGORY_LIST_TYPE);
      return result != null ? result : new ArrayList<>();
    } catch (Exception e) {
      log.error("Failed to fetch categories for topic {}", topicId, e);
      return new ArrayList<>();
    }
  }

  private List<PromotionCategoryDto> fetchAllCategories() {
    try {
      PageResponse<PromotionCategoryDto> result =
          backendApiClient.get("/api/v1/promotion/categories?size=1000", CATEGORY_PAGE_TYPE);
      return result != null && result.content() != null ? result.content() : new ArrayList<>();
    } catch (Exception e) {
      log.error("Failed to fetch all categories", e);
      return new ArrayList<>();
    }
  }

  private List<PromotionLevelContentDto> fetchLevelContents(String categoryId) {
    try {
      List<PromotionLevelContentDto> result =
          backendApiClient.get(
              "/api/v1/promotion/level-contents/by-category/" + categoryId,
              LEVEL_CONTENT_LIST_TYPE);
      return result != null ? result : new ArrayList<>();
    } catch (Exception e) {
      log.error("Failed to fetch level contents for category {}", categoryId, e);
      return new ArrayList<>();
    }
  }

  private List<RankRequirementDto> fetchAllRankRequirements() {
    try {
      PageResponse<RankRequirementDto> result =
          backendApiClient.get(
              "/api/v1/promotion/rank-requirements?size=1000&sort=fromRank",
              RANK_REQUIREMENT_PAGE_TYPE);
      return result != null && result.content() != null ? result.content() : new ArrayList<>();
    } catch (Exception e) {
      log.error("Failed to fetch rank requirements", e);
      return new ArrayList<>();
    }
  }

  /**
   * Returns the signed-in user's rank for the "you are here" marker.
   *
   * @return the user's rank, or {@code null} when unset or the lookup fails
   */
  @Nullable
  private Integer fetchCurrentUserRank() {
    try {
      de.greluc.krt.profit.basetool.frontend.model.dto.UserDto me =
          backendApiClient.get("/api/v1/users/me", USER_TYPE);
      return me != null ? me.rank() : null;
    } catch (Exception e) {
      log.warn("Failed to fetch current user rank for promotion overview", e);
      return null;
    }
  }

  private List<MemberEvaluationDto> fetchMyEvaluations() {
    try {
      List<MemberEvaluationDto> result =
          backendApiClient.get("/api/v1/promotion/evaluations/my", MEMBER_EVALUATION_LIST_TYPE);
      return result != null ? result : new ArrayList<>();
    } catch (Exception e) {
      log.error("Failed to fetch my evaluations", e);
      return new ArrayList<>();
    }
  }

  /**
   * Fetches every page of the squadron-scoped evaluations via {@link CatalogPages#fetchAll}
   * (REQ-PROMO-001); a backend error yields an empty catalogue.
   *
   * @return the complete evaluation catalogue with its truncation flag
   */
  private CompleteCatalog<MemberEvaluationDto> fetchAllEvaluations() {
    try {
      return CatalogPages.fetchAll(
          page ->
              backendApiClient.get(
                  "/api/v1/promotion/evaluations/all?size="
                      + MATRIX_FETCH_PAGE_SIZE
                      + "&page="
                      + page,
                  MEMBER_EVALUATION_PAGE_TYPE));
    } catch (Exception e) {
      log.error("Failed to fetch all evaluations", e);
      return CompleteCatalog.empty();
    }
  }

  /**
   * Fetches every page of the squadron's evaluatable members via {@link CatalogPages#fetchAll}
   * (REQ-PROMO-001); admins and officers are excluded by the backend, and a backend error yields an
   * empty catalogue.
   *
   * @return the complete member catalogue with its truncation flag
   */
  private CompleteCatalog<de.greluc.krt.profit.basetool.frontend.model.dto.UserDto> fetchMembers() {
    try {
      return CatalogPages.fetchAll(
          page ->
              backendApiClient.get(
                  "/api/v1/promotion/evaluations/members?size="
                      + MATRIX_FETCH_PAGE_SIZE
                      + "&page="
                      + page,
                  USER_PAGE_TYPE));
    } catch (Exception e) {
      log.error("Failed to fetch evaluatable members", e);
      return CompleteCatalog.empty();
    }
  }

  private List<PromotionEligibilityDto> fetchMyEligibility() {
    try {
      List<PromotionEligibilityDto> result =
          backendApiClient.get("/api/v1/promotion/eligibility/my", ELIGIBILITY_LIST_TYPE);
      return result != null ? result : new ArrayList<>();
    } catch (Exception e) {
      log.error("Failed to fetch personal promotion eligibility", e);
      return new ArrayList<>();
    }
  }

  private List<PromotionEligibilityDto> fetchEligibilityForUser(String userId) {
    try {
      List<PromotionEligibilityDto> result =
          backendApiClient.get(
              "/api/v1/promotion/eligibility/user/" + userId, ELIGIBILITY_LIST_TYPE);
      return result != null ? result : new ArrayList<>();
    } catch (Exception e) {
      log.error("Failed to fetch promotion eligibility for member {}", userId, e);
      return new ArrayList<>();
    }
  }
}
