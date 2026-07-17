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
   * Chunk size for the {@link CatalogPages#fetchAll page walks} feeding the evaluation matrix
   * (evaluations and members). This is a <b>chunk size</b>, not a cap: the walk keeps requesting
   * pages until the backend reports the last one, so the matrix stays complete at any data volume
   * (REQ-PROMO-001) — unlike the former one-shot {@code ?size=10000} request whose overflow was
   * silently dropped. A squadron that fits one chunk still costs exactly one request.
   */
  private static final int MATRIX_FETCH_PAGE_SIZE = 1000;

  private final BackendApiClient backendApiClient;

  /**
   * Throws {@link AccessDeniedException} when the per-squadron promotion-feature flag is off for
   * the current caller, so every {@code @GetMapping} below short-circuits with HTTP 403 instead of
   * rendering the page. The advisor-supplied flag (admin override + active-org-unit lookup) is
   * passed in as a {@link ModelAttribute} parameter so the controller does not duplicate the
   * resolution logic that {@code CapabilityFlagsAdvice.promotionFeatureEnabled} already runs once
   * per request.
   *
   * @param enabled value of the {@code promotionFeatureEnabled} model attribute; {@code null} is
   *     treated as enabled (mirrors the advisor's permissive default).
   * @throws AccessDeniedException when {@code Boolean.FALSE.equals(enabled)} resolves true.
   */
  private static void requirePromotionFeature(Boolean enabled) {
    if (Boolean.FALSE.equals(enabled)) {
      throw new AccessDeniedException(
          "Promotion feature is disabled for the caller's squadron; ask an administrator to"
              + " re-enable it.");
    }
  }

  /**
   * Schritt 5: Übersicht Beförderungssystem – öffentlich für alle eingeloggten Nutzer.
   *
   * <p>Reicht zusätzlich den aktuellen Rang des eingeloggten Nutzers (falls vorhanden) an das
   * Template weiter, damit dort ein "du-bist-hier"-Marker auf demjenigen Rangsprung gerendert
   * werden kann, der die nächste Beförderung des Nutzers betrifft ({@code fromRank ==
   * currentUserRank}). Ist der Rang nicht ermittelbar (z. B. Backend down, Guest-User ohne Rang im
   * DTO), wird {@code null} weitergegeben und der Marker einfach nicht angezeigt — die Seite
   * funktioniert weiterhin als reine Übersicht.
   */
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
   * Schritt 6: Meine Bewertungen – nur für den eingeloggten Nutzer selbst. Lädt zusätzlich die
   * Beförderbarkeits-Auswertung pro konfigurierter Rangstufen-Kombination und reicht sie an das
   * Template weiter, damit der Nutzer sieht, welche Rangsprünge bereits erreichbar sind und welche
   * Anforderungen noch fehlen.
   *
   * <p>Zusätzlich wird {@code requiredLevelByCategory} berechnet: pro Kategorie die höchste
   * Mindeststufe, die irgendeine Rangvoraussetzung verlangt. Das Template kann damit Kategorien
   * markieren, in denen die eigene Bewertung unter dem höchsten Anforderungslevel liegt
   * ("Schwachstellen"-Highlighting), ohne dass der Nutzer alle Beförderbarkeits-Karten
   * gegenüberstellen muss.
   */
  @GetMapping("/my-evaluations")
  public String myEvaluations(
      @ModelAttribute("promotionFeatureEnabled") Boolean promotionFeatureEnabled, Model model) {
    requirePromotionFeature(promotionFeatureEnabled);
    List<PromotionTopicDto> topics = fetchTopics();
    List<MemberEvaluationDto> myEvaluations = fetchMyEvaluations();

    // Build a map: categoryId -> evaluation for quick lookup
    Map<String, MemberEvaluationDto> evaluationByCategoryId = new LinkedHashMap<>();
    for (MemberEvaluationDto eval : myEvaluations) {
      evaluationByCategoryId.put(eval.categoryId().toString(), eval);
    }

    Map<String, List<PromotionCategoryDto>> topicCategoryMap = new LinkedHashMap<>();
    for (PromotionTopicDto topic : topics) {
      List<PromotionCategoryDto> categories = fetchCategoriesByTopic(topic.id().toString());
      topicCategoryMap.put(topic.id().toString(), categories);
    }

    // Per category the strictest minimum level any rank requirement demands. Used by the
    // template to highlight categories where the user's assigned level falls below the
    // highest expectation across all promotion steps. PromotionLevel ordering is A < B < C.
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
   * Returns a positive number iff {@code a} is a strictly higher promotion level than {@code b}.
   * The ordering matches {@link de.greluc.krt.profit.basetool.backend.model.PromotionLevel} on the
   * backend (LEVEL_A &lt; LEVEL_B &lt; LEVEL_C). Unknown or null inputs sort below known values so
   * the highest-known wins when iterating.
   *
   * @param a first level identifier (e.g. {@code "LEVEL_B"}); may be {@code null}
   * @param b second level identifier; may be {@code null}
   * @return positive iff {@code a > b}, negative iff {@code a < b}, zero otherwise
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
   * Schritt 7: Bewertungsverwaltung – nur für ADMIN und OFFICER. Reicht zusätzlich die pro Mitglied
   * vorausberechnete Beförderbarkeitsliste an das Template; die Offiziere sehen dadurch unmittelbar
   * in der Übersicht, welcher Spieler für welche Beförderung bereit ist.
   *
   * <p>Beyond the flat list of categories the view also receives a stable {@code categoriesByTopic}
   * map so the template can render a two-row header (topic group on top, categories beneath) and a
   * {@code categoryCountByTopic} map so each topic header cell knows the exact colspan to use. The
   * order of {@link PromotionTopicDto} entries in {@code topics} matches the column order of {@code
   * allCategories}, which is what the row body relies on.
   */
  @GetMapping("/manage")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public String manage(
      @ModelAttribute("promotionFeatureEnabled") Boolean promotionFeatureEnabled,
      @RequestParam(required = false) String fragment,
      @RequestParam(required = false) String userId,
      Model model) {
    requirePromotionFeature(promotionFeatureEnabled);

    // Cheap single-member eligibility re-render for the in-place evaluation save flow
    // (REQ-FE-005): after a grade is stored the member's promotability may have flipped,
    // so the client re-fetches just this one eligibility cell instead of reloading the
    // whole matrix. Only this member's eligibility is queried, keeping the call lightweight
    // even when the squadron has hundreds of evaluations.
    if ("eligibilityCell".equals(fragment) && userId != null && !userId.isBlank()) {
      model.addAttribute("eligList", fetchEligibilityForUser(userId));
      return "promotion-manage :: eligibilityCell";
    }

    List<PromotionTopicDto> topics = fetchTopics();
    // Flat list of all categories in topic-then-sortOrder order. Built in lock-step with the
    // per-topic map so the template can iterate row cells against the flat list and header
    // cells against the grouped map without re-sorting on the view layer.
    List<PromotionCategoryDto> allCategories = new ArrayList<>();
    Map<String, List<PromotionCategoryDto>> categoriesByTopic = new LinkedHashMap<>();
    Map<String, Integer> categoryCountByTopic = new LinkedHashMap<>();
    for (PromotionTopicDto topic : topics) {
      List<PromotionCategoryDto> topicCategories = fetchCategoriesByTopic(topic.id().toString());
      categoriesByTopic.put(topic.id().toString(), topicCategories);
      categoryCountByTopic.put(topic.id().toString(), topicCategories.size());
      allCategories.addAll(topicCategories);
    }

    // Fetch all evaluations for admin view — the COMPLETE set via a page walk (REQ-PROMO-001), not
    // one capped chunk, so no member/category cell is silently missing from the matrix.
    CompleteCatalog<MemberEvaluationDto> evaluationsCatalog = fetchAllEvaluations();
    List<MemberEvaluationDto> allEvaluations = evaluationsCatalog.items();
    // Build map: userId+categoryId -> evaluation
    Map<String, MemberEvaluationDto> evaluationMap = new LinkedHashMap<>();
    // Per-user latest updatedAt across all categories. Used by the template to render a
    // "letzte Aenderung am" tooltip on the member cell so officers can spot members whose
    // assessment has gone stale without having to scan every column.
    Map<String, java.time.Instant> lastEvaluatedByUser = new LinkedHashMap<>();
    // Per-user "has at least one stored evaluation" flag. Used by the client-side filter
    // "nur Mitglieder ohne Bewertung" so it does not have to inspect every cell in the row.
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

    // Fetch all members — likewise the COMPLETE, page-walked axis (REQ-PROMO-001).
    CompleteCatalog<de.greluc.krt.profit.basetool.frontend.model.dto.UserDto> membersCatalog =
        fetchMembers();
    List<de.greluc.krt.profit.basetool.frontend.model.dto.UserDto> members = membersCatalog.items();

    // Eligibility per member, keyed by member.id (stringified UUID) so the template can
    // look it up cheaply. Failures for a single member don't break the whole page.
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
    // Either matrix axis stopping at the page-walk safety cap means the rendered matrix is
    // incomplete — surface it with a loud banner rather than showing silent holes that read as
    // "not yet evaluated" (REQ-PROMO-001, mirrors REQ-ADMIN-002). The flag lives inside the
    // matrixBody fragment so it is present on both the full render and the in-place re-render.
    model.addAttribute(
        "matrixTruncated", evaluationsCatalog.truncated() || membersCatalog.truncated());
    // matrixBody is the authoritative full re-render used to recover from an optimistic-lock
    // conflict in place: it rebuilds every row with fresh @Version, level, eligibility and
    // last-evaluated state — exactly what the old full-page reload produced, minus the navigation.
    if ("matrixBody".equals(fragment)) {
      return "promotion-manage :: matrixBody";
    }
    return "promotion-manage";
  }

  /** Schritt 8: Admin-Bereich – Themenbereiche, Kategorien & Stufeninhalte verwalten. */
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
   * Schritt 8: Admin-Bereich – Rangvoraussetzungen verwalten. The admin view groups the flat list
   * of requirements by their {@code (fromRank, toRank)} pair so each promotion step is rendered as
   * one section with its own table, mirroring the read-only layout used on {@code
   * /promotion/overview}.
   *
   * @param promotionFeatureEnabled per-squadron feature flag; {@code false} short-circuits with 403
   * @param fragment when {@code "ranksResults"}, only the requirements-list fragment is rendered
   *     (for an in-place AJAX swap after a create/edit/delete) instead of the whole page
   * @param model Spring MVC model populated with the grouped requirements, topics and categories
   * @return the Thymeleaf view (or {@code view :: fragment}) name for the rank-requirements admin
   *     page
   */
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

    // categoriesByTopic powers the cascading Topic -> Category dropdown in the
    // create/edit modals: when the admin picks a topic, the client filters the
    // category dropdown to that topic's categories only, so a category from a
    // different topic can no longer be combined with a topic accidentally.
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

  // ---------------------------------------------------------------------------------
  // Private helper methods
  // ---------------------------------------------------------------------------------

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
   * Returns the rank of the currently authenticated user, or {@code null} if no rank is set or the
   * {@code /me} call fails. Used by the overview and my-evaluations pages to render a
   * "you-are-here" marker on the relevant promotion step. A {@code null} return is non-fatal — the
   * calling templates render without the marker rather than failing the whole page.
   *
   * @return the user's rank as an {@link Integer}, or {@code null} when unavailable
   */
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
   * Walks <b>every</b> page of the squadron-scoped evaluation listing. Evaluations grow
   * multiplicatively (members × categories), so no single fixed page size is safe: the former
   * one-shot {@code ?size=10000} request silently dropped everything past the bound and the matrix
   * rendered holes indistinguishable from "not yet evaluated" (REQ-PROMO-001). The shared {@link
   * CatalogPages#fetchAll} page walk (ADR-0102) assembles the complete set and reports whether it
   * had to stop at its safety cap; the caller surfaces that truncation loudly. A backend error is
   * swallowed to an empty catalogue, preserving the page's established fail-soft-to-empty
   * behaviour.
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
   * Walks <b>every</b> page of the squadron's evaluatable members. The backend's {@code
   * /api/v1/promotion/evaluations/members} endpoint applies the same scope as {@code
   * SquadronScopeService.currentSquadronId()} (Officer = own squadron, Admin = focused squadron or
   * all squadrons) and excludes any user that carries the Admin OR the Officer role (issue #817):
   * admins are squadron-less by design, and officers run the Bewertungsverwaltung rather than being
   * its subject, so neither belongs in the matrix — it assesses only the ordinary members of the
   * squadron. Fetched completely via {@link CatalogPages#fetchAll} so a squadron beyond the page
   * size never silently loses matrix rows (REQ-PROMO-001); a backend error degrades to an empty
   * catalogue.
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
