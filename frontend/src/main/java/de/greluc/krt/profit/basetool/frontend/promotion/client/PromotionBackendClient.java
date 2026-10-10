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

package de.greluc.krt.profit.basetool.frontend.promotion.client;

import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.promotion.model.MemberEvaluationDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.MemberEvaluationUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionCategoryDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionCategoryWriteRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionEligibilityDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionLevelContentDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionLevelContentWriteRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionTopicDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionTopicWriteRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.RankRequirementDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.RankRequirementWriteRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Typed backend client of the promotion domain: the topics, categories, level texts and rank
 * requirements, the evaluations and the eligibility (REQ-PROMO-001), over {@link BackendApiClient}
 * (plan §5.9, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class PromotionBackendClient {

  /** The backend's promotion-topic resource. */
  private static final String TOPIC = "/api/v1/promotion/topics/{id}";

  /** The backend's promotion-category resource. */
  private static final String CATEGORY = "/api/v1/promotion/categories/{id}";

  /** The backend's rank-requirement resource. */
  private static final String RANK_REQUIREMENT = "/api/v1/promotion/rank-requirements/{id}";

  /** The backend's level-text resource. */
  private static final String LEVEL_CONTENT = "/api/v1/promotion/level-contents/{id}";

  private static final ParameterizedTypeReference<List<PromotionTopicDto>> TOPIC_LIST =
      new ParameterizedTypeReference<List<PromotionTopicDto>>() {};

  private static final ParameterizedTypeReference<List<PromotionCategoryDto>> CATEGORY_LIST =
      new ParameterizedTypeReference<List<PromotionCategoryDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<PromotionCategoryDto>>
      CATEGORY_PAGE = new ParameterizedTypeReference<PageResponse<PromotionCategoryDto>>() {};

  private static final ParameterizedTypeReference<List<PromotionLevelContentDto>>
      LEVEL_CONTENT_LIST = new ParameterizedTypeReference<List<PromotionLevelContentDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<RankRequirementDto>>
      RANK_REQUIREMENT_PAGE = new ParameterizedTypeReference<PageResponse<RankRequirementDto>>() {};

  private static final ParameterizedTypeReference<UserDto> USER =
      new ParameterizedTypeReference<UserDto>() {};

  private static final ParameterizedTypeReference<List<MemberEvaluationDto>> EVALUATION_LIST =
      new ParameterizedTypeReference<List<MemberEvaluationDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<MemberEvaluationDto>>
      EVALUATION_PAGE = new ParameterizedTypeReference<PageResponse<MemberEvaluationDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<UserDto>> USER_PAGE =
      new ParameterizedTypeReference<PageResponse<UserDto>>() {};

  private static final ParameterizedTypeReference<List<PromotionEligibilityDto>> ELIGIBILITY_LIST =
      new ParameterizedTypeReference<List<PromotionEligibilityDto>>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Lists every promotion topic.
   *
   * @return the topics, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<PromotionTopicDto> topics() {
    return backendApiClient.get("/api/v1/promotion/topics/all", TOPIC_LIST);
  }

  /**
   * Lists every category of one topic.
   *
   * @param topicId the topic, as the page received it
   * @return the categories, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<PromotionCategoryDto> categoriesByTopic(@Nullable String topicId) {
    return backendApiClient.get(
        "/api/v1/promotion/categories/by-topic/{topicId}/all", CATEGORY_LIST, topicId);
  }

  /**
   * Reads the first thousand categories of every topic.
   *
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<PromotionCategoryDto> allCategories() {
    return backendApiClient.get("/api/v1/promotion/categories?size=1000", CATEGORY_PAGE);
  }

  /**
   * Lists the level texts of one category.
   *
   * @param categoryId the category, as the page received it
   * @return the level texts, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<PromotionLevelContentDto> levelContents(@Nullable String categoryId) {
    return backendApiClient.get(
        "/api/v1/promotion/level-contents/by-category/{categoryId}",
        LEVEL_CONTENT_LIST,
        categoryId);
  }

  /**
   * Reads the first thousand rank requirements, ordered by the rank they start at.
   *
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<RankRequirementDto> rankRequirements() {
    return backendApiClient.get(
        "/api/v1/promotion/rank-requirements?size=1000&sort=fromRank", RANK_REQUIREMENT_PAGE);
  }

  /**
   * Reads the signed-in user, whose rank marks the next rank step.
   *
   * @return the user, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto currentUser() {
    return backendApiClient.get("/api/v1/users/me", USER);
  }

  /**
   * Lists the signed-in user's own evaluations.
   *
   * @return the evaluations, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MemberEvaluationDto> myEvaluations() {
    return backendApiClient.get("/api/v1/promotion/evaluations/my", EVALUATION_LIST);
  }

  /**
   * Reads one page of the evaluations in the caller's squadron scope.
   *
   * @param size the page size
   * @param page the zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MemberEvaluationDto> allEvaluations(int size, int page) {
    return backendApiClient.get(
        "/api/v1/promotion/evaluations/all?size={size}&page={page}", EVALUATION_PAGE, size, page);
  }

  /**
   * Reads one page of the squadron members the caller may evaluate.
   *
   * @param size the page size
   * @param page the zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<UserDto> evaluatableMembers(int size, int page) {
    return backendApiClient.get(
        "/api/v1/promotion/evaluations/members?size={size}&page={page}", USER_PAGE, size, page);
  }

  /**
   * Lists the signed-in user's promotion eligibility per rank step.
   *
   * @return the eligibility, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<PromotionEligibilityDto> myEligibility() {
    return backendApiClient.get("/api/v1/promotion/eligibility/my", ELIGIBILITY_LIST);
  }

  /**
   * Lists one member's promotion eligibility per rank step.
   *
   * @param userId the member
   * @return the eligibility, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<PromotionEligibilityDto> eligibilityOf(@NotNull UUID userId) {
    return backendApiClient.get(
        "/api/v1/promotion/eligibility/user/{userId}", ELIGIBILITY_LIST, userId);
  }

  /**
   * Creates a promotion topic.
   *
   * @param request the new topic
   * @return the created topic, or {@code null} when the backend sent no body
   */
  @Nullable
  public PromotionTopicDto createTopic(@NotNull PromotionTopicWriteRequest request) {
    return backendApiClient.post("/api/v1/promotion/topics", request, PromotionTopicDto.class);
  }

  /**
   * Updates a promotion topic, carrying the optimistic-lock version in the request.
   *
   * @param id the topic
   * @param request the edited topic
   * @return the updated topic, or {@code null} when the backend sent no body
   */
  @Nullable
  public PromotionTopicDto updateTopic(
      @NotNull UUID id, @NotNull PromotionTopicWriteRequest request) {
    return backendApiClient.put(TOPIC, request, PromotionTopicDto.class, id);
  }

  /**
   * Deletes a promotion topic.
   *
   * @param id the topic
   */
  public void deleteTopic(@NotNull UUID id) {
    backendApiClient.delete(TOPIC, Void.class, id);
  }

  /**
   * Creates a promotion category.
   *
   * @param request the new category
   * @return the created category, or {@code null} when the backend sent no body
   */
  @Nullable
  public PromotionCategoryDto createCategory(@NotNull PromotionCategoryWriteRequest request) {
    return backendApiClient.post(
        "/api/v1/promotion/categories", request, PromotionCategoryDto.class);
  }

  /**
   * Updates a promotion category, carrying the optimistic-lock version in the request.
   *
   * @param id the category
   * @param request the edited category
   * @return the updated category, or {@code null} when the backend sent no body
   */
  @Nullable
  public PromotionCategoryDto updateCategory(
      @NotNull UUID id, @NotNull PromotionCategoryWriteRequest request) {
    return backendApiClient.put(CATEGORY, request, PromotionCategoryDto.class, id);
  }

  /**
   * Deletes a promotion category.
   *
   * @param id the category
   */
  public void deleteCategory(@NotNull UUID id) {
    backendApiClient.delete(CATEGORY, Void.class, id);
  }

  /**
   * Creates a rank requirement.
   *
   * @param request the new requirement
   * @return the created requirement, or {@code null} when the backend sent no body
   */
  @Nullable
  public RankRequirementDto createRankRequirement(@NotNull RankRequirementWriteRequest request) {
    return backendApiClient.post(
        "/api/v1/promotion/rank-requirements", request, RankRequirementDto.class);
  }

  /**
   * Updates a rank requirement, carrying the optimistic-lock version in the request.
   *
   * @param id the requirement
   * @param request the edited requirement
   * @return the updated requirement, or {@code null} when the backend sent no body
   */
  @Nullable
  public RankRequirementDto updateRankRequirement(
      @NotNull UUID id, @NotNull RankRequirementWriteRequest request) {
    return backendApiClient.put(RANK_REQUIREMENT, request, RankRequirementDto.class, id);
  }

  /**
   * Deletes a rank requirement.
   *
   * @param id the requirement
   */
  public void deleteRankRequirement(@NotNull UUID id) {
    backendApiClient.delete(RANK_REQUIREMENT, Void.class, id);
  }

  /**
   * Creates a level text.
   *
   * @param request the new level text
   * @return the created level text, or {@code null} when the backend sent no body
   */
  @Nullable
  public PromotionLevelContentDto createLevelContent(
      @NotNull PromotionLevelContentWriteRequest request) {
    return backendApiClient.post(
        "/api/v1/promotion/level-contents", request, PromotionLevelContentDto.class);
  }

  /**
   * Updates a level text, carrying the optimistic-lock version in the request.
   *
   * @param id the level text
   * @param request the edited level text
   * @return the updated level text, or {@code null} when the backend sent no body
   */
  @Nullable
  public PromotionLevelContentDto updateLevelContent(
      @NotNull UUID id, @NotNull PromotionLevelContentWriteRequest request) {
    return backendApiClient.put(LEVEL_CONTENT, request, PromotionLevelContentDto.class, id);
  }

  /**
   * Deletes a level text.
   *
   * @param id the level text
   */
  public void deleteLevelContent(@NotNull UUID id) {
    backendApiClient.delete(LEVEL_CONTENT, Void.class, id);
  }

  /**
   * Creates or updates a member's evaluation in one category.
   *
   * @param userId the evaluated member
   * @param categoryId the category
   * @param request the assigned level and the evaluation's version
   * @return the stored evaluation, or {@code null} when the backend sent no body
   */
  @Nullable
  public MemberEvaluationDto upsertEvaluation(
      @NotNull UUID userId,
      @NotNull UUID categoryId,
      @NotNull MemberEvaluationUpdateRequest request) {
    String uri =
        UriComponentsBuilder.fromPath(
                "/api/v1/promotion/evaluations/user/{userId}/category/{categoryId}")
            .buildAndExpand(userId, categoryId)
            .toUriString();
    return backendApiClient.put(uri, request, MemberEvaluationDto.class);
  }
}
